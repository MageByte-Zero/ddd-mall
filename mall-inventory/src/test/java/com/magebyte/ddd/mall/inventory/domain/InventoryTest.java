package com.magebyte.ddd.mall.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存聚合守卫的纯单元测试（无中间件）。
 */
class InventoryTest {

    private Inventory inventory(int available) {
        return Inventory.reconstitute(1L, "SKU-1001", "示例商品",
                100, available, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void deduct_decreases_available_stock() {
        Inventory inventory = inventory(100);
        inventory.deduct(3);
        assertEquals(97, inventory.availableStock());
    }

    @Test
    void deduct_rejects_insufficient_stock() {
        Inventory inventory = inventory(2);
        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventory.deduct(3));
        assertTrue(ex.getMessage().contains("库存不足"));
        // 失败后库存不变
        assertEquals(2, inventory.availableStock());
    }

    @Test
    void deduct_rejects_non_positive_quantity() {
        Inventory inventory = inventory(100);
        assertThrows(InventoryDomainException.class, () -> inventory.deduct(0));
        assertThrows(InventoryDomainException.class, () -> inventory.deduct(-5));
        assertEquals(100, inventory.availableStock());
    }

    @Test
    void reconstitute_rejects_corrupted_persistence_state() {
        // 可售 > 总库存，违反库存守恒
        assertThrows(InventoryDomainException.class, () ->
                Inventory.reconstitute(1L, "SKU-1001", "示例商品",
                        10, 20, LocalDateTime.now(), LocalDateTime.now()));
    }
}
