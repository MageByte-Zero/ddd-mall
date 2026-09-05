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
 */
@SpringBootTest
@Transactional
class InventoryApplicationServiceTest {

    @Autowired
    private InventoryApplicationService inventoryApplicationService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    void deduct_decreases_available_stock() {
        Inventory before = inventoryRepository.findBySkuCode("SKU-1001").orElseThrow();
        int baseline = before.availableStock();

        inventoryApplicationService.deduct("SKU-1001", 3);

        Inventory after = inventoryRepository.findBySkuCode("SKU-1001").orElseThrow();
        assertEquals(baseline - 3, after.availableStock());
    }

    @Test
    void deduct_insufficient_stock_throws_and_changes_nothing() {
        Inventory before = inventoryRepository.findBySkuCode("SKU-1001").orElseThrow();

        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventoryApplicationService.deduct("SKU-1001", baselinePlusOne(before)));
        assertTrue(ex.getMessage().contains("库存不足"));

        Inventory after = inventoryRepository.findBySkuCode("SKU-1001").orElseThrow();
        assertEquals(before.availableStock(), after.availableStock());
    }

    @Test
    void deduct_unknown_sku_throws() {
        assertThrows(InventoryDomainException.class,
                () -> inventoryApplicationService.deduct("SKU-NO-SUCH-SKU", 1));
    }

    private int baselinePlusOne(Inventory before) {
        return before.availableStock() + 1;
    }
}
