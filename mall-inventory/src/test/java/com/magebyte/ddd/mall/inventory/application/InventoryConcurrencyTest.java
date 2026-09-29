package com.magebyte.ddd.mall.inventory.application;

import com.magebyte.ddd.mall.inventory.domain.Inventory;
import com.magebyte.ddd.mall.inventory.domain.InventoryConcurrencyException;
import com.magebyte.ddd.mall.inventory.domain.InventoryDomainException;
import com.magebyte.ddd.mall.inventory.domain.InventoryRepository;
import com.magebyte.ddd.mall.inventory.domain.StockSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存并发实验：第 12 讲全部结论的数字来源。
 *
 * <p>这里<b>不加 {@code @Transactional}</b>：要观察并发，每个请求必须真的提交自己的事务。
 * 加了类级事务，所有线程会共用测试的同一个连接和同一个快照，测出来的东西毫无意义。
 *
 * <p>三个实验分别回答三个问题：
 * <ol>
 *   <li>库存只有 50 件、1000 个请求各抢 1 件，会不会卖超？</li>
 *   <li>库存充足时，乐观锁整体保存与原子条件更新，谁的吞吐更高、谁在丢请求？</li>
 *   <li>走乐观锁的释放路径，并发下会撞成什么样？</li>
 * </ol>
 */
@SpringBootTest
class InventoryConcurrencyTest {

    private static final String SKU = "SKU-1001";
    /** 并发度：受 Hikari 连接池与 MySQL max_connections 共同约束，不是越大越真实。 */
    private static final int CONCURRENCY = 64;
    private static final int REQUESTS = 1000;

    @Autowired
    private InventoryApplicationService inventoryApplicationService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreSeed() {
        reset(100, 100, 0);
    }

    @Test
    void reserve_never_oversells_when_1000_requests_fight_for_50_items() {
        int stock = 50;
        reset(stock, stock, 0);

        Stats stats = runConcurrently(REQUESTS, () -> {
            try {
                inventoryApplicationService.reserve(SKU, 1);
                return Outcome.SUCCESS;
            } catch (InventoryDomainException ex) {
                return Outcome.REJECTED;
            }
        });

        Inventory after = inventoryRepository.findBySkuCode(SKU).orElseThrow();
        System.out.println("[防超卖] 库存 " + stock + " 件 / " + REQUESTS + " 个请求各抢 1 件 → "
                + "成功 " + stats.success + "，被拒 " + stats.rejected + "，异常 " + stats.failed
                + "，耗时 " + stats.elapsedMs + " ms；终态 总 " + after.totalStock()
                + " / 可售 " + after.availableStock() + " / 已预占 " + after.reservedStock());

        assertEquals(stock, stats.success, "能卖的必须全部卖掉，一件都不能少卖");
        assertEquals(stock, after.reservedStock(), "卖掉的都进了已预占");
        assertEquals(0, after.availableStock(), "可售必须被抢光");
        assertEquals(stock, after.totalStock(), "预占不动总库存");
        assertConservation(after);
        assertEquals(0, stats.failed, "不允许出现库存不足/冲突之外的异常");
    }

    @Test
    void optimistic_lock_vs_atomic_update_under_1000_concurrent_reservations() {
        int stock = REQUESTS;
        reset(stock, stock, 0);

        // 策略 A：聚合整体保存 + 乐观锁（主流 DDD 写法）
        Stats optimistic = runConcurrently(REQUESTS, () -> {
            try {
                Inventory inventory = inventoryRepository.findBySkuCode(SKU).orElseThrow();
                StockSnapshot before = inventory.snapshot();
                inventory.reserve(1);
                int rows = inventoryRepository.save(inventory);
                if (rows == 0) {
                    return Outcome.REJECTED;   // 版本冲突：别人先动了这一行
                }
                inventoryRepository.appendLog(SKU, "RESERVE", 1, before, inventory.snapshot());
                return Outcome.SUCCESS;
            } catch (InventoryDomainException ex) {
                return Outcome.REJECTED;
            }
        });
        Inventory afterOptimistic = inventoryRepository.findBySkuCode(SKU).orElseThrow();
        assertConservation(afterOptimistic);
        assertEquals(optimistic.success, afterOptimistic.reservedStock());

        // 策略 B：原子条件更新（本讲预占路径的采用方案）
        reset(stock, stock, 0);
        Stats atomic = runConcurrently(REQUESTS, () -> {
            try {
                inventoryApplicationService.reserve(SKU, 1);
                return Outcome.SUCCESS;
            } catch (InventoryDomainException ex) {
                return Outcome.REJECTED;
            }
        });
        Inventory afterAtomic = inventoryRepository.findBySkuCode(SKU).orElseThrow();
        assertConservation(afterAtomic);
        assertEquals(atomic.success, afterAtomic.reservedStock());

        System.out.println("[写策略对比] 库存 " + stock + " 件 / " + REQUESTS + " 个请求各抢 1 件，并发度 " + CONCURRENCY);
        System.out.println("  乐观锁整体保存 : 成功 " + optimistic.success + "，冲突 " + optimistic.rejected
                + "，耗时 " + optimistic.elapsedMs + " ms");
        System.out.println("  原子条件更新   : 成功 " + atomic.success + "，被拒 " + atomic.rejected
                + "，耗时 " + atomic.elapsedMs + " ms");

        assertEquals(REQUESTS, atomic.success, "库存充足时原子更新应当一件不丢");
        assertTrue(optimistic.success < atomic.success,
                "同一并发度下乐观锁会丢请求：冲突的请求没有重试，直接被拒");
        assertTrue(optimistic.rejected > 0, "本实验的前提是确实发生了版本冲突，否则对比无意义");
    }

    @Test
    void concurrent_release_hits_optimistic_lock_conflicts() {
        int reserved = 100;
        reset(reserved, 0, reserved);

        AtomicInteger conflicts = new AtomicInteger();
        Stats stats = runConcurrently(REQUESTS / 10, () -> {
            try {
                inventoryApplicationService.release(SKU, 1);
                return Outcome.SUCCESS;
            } catch (InventoryConcurrencyException ex) {
                conflicts.incrementAndGet();
                return Outcome.REJECTED;
            } catch (InventoryDomainException ex) {
                return Outcome.REJECTED;
            }
        });

        Inventory after = inventoryRepository.findBySkuCode(SKU).orElseThrow();
        System.out.println("[并发释放] 已预占 " + reserved + " 件 / " + (REQUESTS / 10)
                + " 个并发释放请求 → 成功 " + stats.success + "，版本冲突 " + conflicts.get()
                + "，耗时 " + stats.elapsedMs + " ms；终态 总 " + after.totalStock()
                + " / 可售 " + after.availableStock() + " / 已预占 " + after.reservedStock());

        assertTrue(conflicts.get() > 0, "并发释放必须真的撞到乐观锁，否则这条路径没被验证");
        assertEquals(reserved - stats.success, after.reservedStock(), "释放掉的件数必须等于成功数");
        assertConservation(after);
    }

    private enum Outcome { SUCCESS, REJECTED, FAILED }

    private record Stats(int success, int rejected, int failed, long elapsedMs) {
    }

    private Stats runConcurrently(int requests, java.util.concurrent.Callable<Outcome> task) {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(requests);
        try {
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        switch (task.call()) {
                            case SUCCESS -> success.incrementAndGet();
                            case REJECTED -> rejected.incrementAndGet();
                            default -> failed.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                    }
                    return null;
                }));
            }
            long begin = System.currentTimeMillis();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
            long elapsed = System.currentTimeMillis() - begin;
            return new Stats(success.get(), rejected.get(), failed.get(), elapsed);
        } catch (Exception ex) {
            throw new IllegalStateException("并发实验执行失败", ex);
        } finally {
            pool.shutdownNow();
        }
    }

    private void reset(int total, int available, int reserved) {
        jdbcTemplate.update("UPDATE t_inventory SET total_stock = ?, available_stock = ?, reserved_stock = ?"
                + " WHERE sku_code = ?", total, available, reserved, SKU);
    }

    private void assertConservation(Inventory inventory) {
        assertEquals(inventory.totalStock(),
                inventory.availableStock() + inventory.reservedStock(),
                "库存守恒被破坏：可售 + 已预占 必须等于总库存");
    }
}
