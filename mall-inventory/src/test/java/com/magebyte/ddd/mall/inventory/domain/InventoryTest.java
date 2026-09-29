package com.magebyte.ddd.mall.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存聚合守卫的纯单元测试（无中间件、无数据库）。
 *
 * <p>第 12 讲把它从"扣减/归还"两套守卫改成"预占/确认/释放/补货"四套 + 一条守恒不变量。
 * 守恒是这里唯一的核心：每个用例除了断言自己关心的那个数，还要断言三个数加起来仍然对得上。
 */
class InventoryTest {

    private static final LocalDateTime NOW = LocalDateTime.now();

    private Inventory inventory(int total, int available, int reserved) {
        return Inventory.reconstitute(1L, "SKU-1001", "示例商品",
                total, available, reserved, 0, NOW, NOW);
    }

    /** 满仓：100 件全可售、没人占。 */
    private Inventory fullStock() {
        return inventory(100, 100, 0);
    }

    @Test
    void reserve_moves_available_to_reserved_and_keeps_total() {
        Inventory inventory = fullStock();
        inventory.reserve(3);
        assertEquals(97, inventory.availableStock());
        assertEquals(3, inventory.reservedStock());
        assertEquals(100, inventory.totalStock(), "预占不动总库存：货还在仓库里");
    }

    @Test
    void reserve_rejects_insufficient_stock() {
        Inventory inventory = inventory(100, 2, 98);
        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventory.reserve(3));
        assertTrue(ex.getMessage().contains("库存不足"));
        assertEquals(2, inventory.availableStock(), "失败后库存不变");
        assertEquals(98, inventory.reservedStock());
    }

    @Test
    void reserve_rejects_non_positive_quantity() {
        Inventory inventory = fullStock();
        assertThrows(InventoryDomainException.class, () -> inventory.reserve(0));
        assertThrows(InventoryDomainException.class, () -> inventory.reserve(-5));
        assertEquals(100, inventory.availableStock());
    }

    @Test
    void confirm_removes_reserved_and_lowers_total() {
        Inventory inventory = inventory(100, 97, 3);
        inventory.confirm(3);
        assertEquals(0, inventory.reservedStock());
        assertEquals(97, inventory.totalStock(), "出库后总库存才真正减少");
        assertEquals(97, inventory.availableStock(), "出库不动可售");
        assertTrue(inventory.snapshot().conservative());
    }

    @Test
    void confirm_rejects_beyond_reserved() {
        Inventory inventory = inventory(100, 97, 3);
        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventory.confirm(4));
        assertTrue(ex.getMessage().contains("超过已预占"));
        assertEquals(3, inventory.reservedStock());
    }

    @Test
    void release_returns_reserved_to_available() {
        Inventory inventory = inventory(100, 90, 10);
        inventory.release(10);
        assertEquals(100, inventory.availableStock());
        assertEquals(0, inventory.reservedStock());
        assertEquals(100, inventory.totalStock(), "释放不动总库存");
    }

    @Test
    void release_rejects_beyond_reserved_even_when_total_allows_it() {
        // 这是本讲修正第 11 讲护栏的关键用例：
        // 订单 A 占 5 件、订单 B 占 5 件、总库存 100、可售 90。
        // 此时"再释放 10 件"在旧护栏（可售 + 10 <= 总库存）下完全合法，
        // 但已预占只有 10 件，释放 11 件就是重复释放。
        Inventory inventory = inventory(100, 90, 10);
        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventory.release(11));
        assertTrue(ex.getMessage().contains("超过已预占"));
        assertEquals(10, inventory.reservedStock());
        assertEquals(90, inventory.availableStock());
    }

    @Test
    void release_rejects_non_positive_quantity() {
        Inventory inventory = inventory(100, 90, 10);
        assertThrows(InventoryDomainException.class, () -> inventory.release(0));
        assertThrows(InventoryDomainException.class, () -> inventory.release(-5));
        assertEquals(10, inventory.reservedStock());
    }

    @Test
    void restock_raises_total_and_available() {
        Inventory inventory = inventory(100, 90, 10);
        inventory.restock(50);
        assertEquals(150, inventory.totalStock());
        assertEquals(140, inventory.availableStock());
        assertEquals(10, inventory.reservedStock(), "补货不动已预占");
        assertTrue(inventory.snapshot().conservative());
    }

    @Test
    void reserve_then_release_returns_to_original() {
        Inventory inventory = fullStock();
        inventory.reserve(30);
        inventory.release(30);
        assertEquals(100, inventory.availableStock());
        assertEquals(0, inventory.reservedStock());
        assertEquals(100, inventory.totalStock());
    }

    @Test
    void reserve_then_confirm_consumes_stock_permanently() {
        Inventory inventory = fullStock();
        inventory.reserve(30);
        inventory.confirm(30);
        assertEquals(70, inventory.totalStock(), "出库后总库存永久减少 30");
        assertEquals(70, inventory.availableStock());
        assertEquals(0, inventory.reservedStock());
        assertTrue(inventory.snapshot().conservative());
    }

    @Test
    void reconstitute_rejects_state_that_breaks_conservation() {
        // 总库存 100、可售 93、已预占 0 —— 正是第 10/11 讲留在库里的那种状态。
        // 它在两字段模型下"看起来正常"，三字段模型下当场就站不住。
        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventory(100, 93, 0));
        assertTrue(ex.getMessage().contains("守恒"));
    }

    @Test
    void reconstitute_rejects_negative_numbers() {
        assertThrows(InventoryDomainException.class, () -> inventory(-1, 0, 0));
        assertThrows(InventoryDomainException.class, () -> inventory(100, -1, 0));
        assertThrows(InventoryDomainException.class, () -> inventory(100, 100, -1));
    }

    @Test
    void snapshot_captures_the_three_numbers_immutably() {
        Inventory inventory = inventory(100, 90, 10);
        StockSnapshot before = inventory.snapshot();
        inventory.reserve(10);
        assertEquals(90, before.availableStock(), "快照不随聚合后续变动而改变");
        assertEquals(80, inventory.availableStock());
        assertTrue(before.conservative());
        assertTrue(inventory.snapshot().conservative());
    }
}
