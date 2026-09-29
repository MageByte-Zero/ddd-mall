package com.magebyte.ddd.mall.inventory.application;

import com.magebyte.ddd.mall.inventory.domain.Inventory;
import com.magebyte.ddd.mall.inventory.domain.InventoryDomainException;
import com.magebyte.ddd.mall.inventory.domain.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存应用服务集成测试：真实 MySQL（seata 自动装配在测试环境关闭，
 * 全局事务行为由真实 jar + TC 的端到端实验覆盖），@Transactional 回滚不残留数据。
 *
 * <p>第 12 讲起所有断言都由"本次请求前后的增减"推导，不假设数据库绝对初值：
 * 读者会先照文章做端到端实验、再跑测试，届时库里的数早就不是种子值了。
 * 这个规矩是第 11 讲用一次假红换来的。
 */
@SpringBootTest
@Transactional
class InventoryApplicationServiceTest {

    private static final String SKU = "SKU-1001";

    @Autowired
    private InventoryApplicationService inventoryApplicationService;

    @Autowired
    private InventoryRepository inventoryRepository;

    private Inventory current() {
        return inventoryRepository.findBySkuCode(SKU).orElseThrow();
    }

    @Test
    void reserve_moves_available_to_reserved_and_keeps_total() {
        Inventory before = current();

        inventoryApplicationService.reserve(SKU, 3);

        Inventory after = current();
        assertEquals(before.availableStock() - 3, after.availableStock());
        assertEquals(before.reservedStock() + 3, after.reservedStock());
        assertEquals(before.totalStock(), after.totalStock(), "预占不动总库存");
        assertConservation(after);
    }

    @Test
    void reserve_insufficient_stock_throws_and_changes_nothing() {
        Inventory before = current();
        int tooMany = before.availableStock() + 1;

        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventoryApplicationService.reserve(SKU, tooMany));
        assertTrue(ex.getMessage().contains("库存不足"));

        Inventory after = current();
        assertEquals(before.availableStock(), after.availableStock());
        assertEquals(before.reservedStock(), after.reservedStock());
    }

    @Test
    void reserve_unknown_sku_throws() {
        assertThrows(InventoryDomainException.class,
                () -> inventoryApplicationService.reserve("SKU-NO-SUCH-SKU", 1));
    }

    @Test
    void release_returns_reserved_to_available() {
        inventoryApplicationService.reserve(SKU, 3);
        Inventory afterReserve = current();

        inventoryApplicationService.release(SKU, 3);

        Inventory afterRelease = current();
        assertEquals(afterReserve.availableStock() + 3, afterRelease.availableStock());
        assertEquals(afterReserve.reservedStock() - 3, afterRelease.reservedStock());
        assertEquals(afterReserve.totalStock(), afterRelease.totalStock());
        assertConservation(afterRelease);
    }

    @Test
    void release_beyond_reserved_throws_and_changes_nothing() {
        inventoryApplicationService.reserve(SKU, 3);
        Inventory before = current();
        // 已预占之外的 1 件：旧护栏（可售 + n <= 总库存）会放行，新护栏必须拦下
        int tooMany = before.reservedStock() + 1;

        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventoryApplicationService.release(SKU, tooMany));
        assertTrue(ex.getMessage().contains("超过已预占"));

        Inventory after = current();
        assertEquals(before.availableStock(), after.availableStock());
        assertEquals(before.reservedStock(), after.reservedStock());
    }

    @Test
    void confirm_lowers_total_and_reserved() {
        inventoryApplicationService.reserve(SKU, 3);
        Inventory afterReserve = current();

        inventoryApplicationService.confirm(SKU, 3);

        Inventory afterConfirm = current();
        assertEquals(afterReserve.totalStock() - 3, afterConfirm.totalStock());
        assertEquals(afterReserve.reservedStock() - 3, afterConfirm.reservedStock());
        assertEquals(afterReserve.availableStock(), afterConfirm.availableStock());
        assertConservation(afterConfirm);
    }

    @Test
    void confirm_beyond_reserved_throws() {
        inventoryApplicationService.reserve(SKU, 3);
        Inventory before = current();

        assertThrows(InventoryDomainException.class,
                () -> inventoryApplicationService.confirm(SKU, before.reservedStock() + 1));
    }

    @Test
    void restock_raises_total_and_available() {
        Inventory before = current();

        inventoryApplicationService.restock(SKU, 50);

        Inventory after = current();
        assertEquals(before.totalStock() + 50, after.totalStock());
        assertEquals(before.availableStock() + 50, after.availableStock());
        assertEquals(before.reservedStock(), after.reservedStock());
        assertConservation(after);
    }

    @Test
    void get_returns_all_three_numbers() {
        inventoryApplicationService.reserve(SKU, 2);

        InventoryView view = inventoryApplicationService.get(SKU);
        Inventory actual = current();
        assertEquals(actual.totalStock(), view.totalStock());
        assertEquals(actual.availableStock(), view.availableStock());
        assertEquals(actual.reservedStock(), view.reservedStock());
    }

    private void assertConservation(Inventory inventory) {
        assertEquals(inventory.totalStock(),
                inventory.availableStock() + inventory.reservedStock(),
                "库存守恒被破坏：可售 + 已预占 必须等于总库存");
    }
}
