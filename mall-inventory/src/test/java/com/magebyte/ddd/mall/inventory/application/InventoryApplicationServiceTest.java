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

    @Test
    void release_increases_available_stock() {
        inventoryApplicationService.deduct("SKU-1001", 3);
        Inventory afterDeduct = inventoryRepository.findBySkuCode("SKU-1001").orElseThrow();

        inventoryApplicationService.release("SKU-1001", 3);

        Inventory afterRelease = inventoryRepository.findBySkuCode("SKU-1001").orElseThrow();
        assertEquals(afterDeduct.availableStock() + 3, afterRelease.availableStock());
    }

    @Test
    void release_beyond_total_stock_throws_and_changes_nothing() {
        Inventory before = inventoryRepository.findBySkuCode("SKU-1001").orElseThrow();

        // 越界量必须由当前状态推导，不能假设"还没扣过、可售 == 总库存"：
        // 跑完本讲的端到端实验后，库里的可售已经小于总库存，此时再归还 1 件
        // 是合法的，写死数量的测试会因环境残留而假红（本讲实测踩到过）。
        int overshoot = before.totalStock() - before.availableStock() + 1;
        InventoryDomainException ex = assertThrows(InventoryDomainException.class,
                () -> inventoryApplicationService.release("SKU-1001", overshoot));
        assertTrue(ex.getMessage().contains("超过总库存"));

        Inventory after = inventoryRepository.findBySkuCode("SKU-1001").orElseThrow();
        assertEquals(before.availableStock(), after.availableStock());
    }

    @Test
    void release_unknown_sku_throws() {
        assertThrows(InventoryDomainException.class,
                () -> inventoryApplicationService.release("SKU-NO-SUCH-SKU", 1));
    }

    private int baselinePlusOne(Inventory before) {
        return before.availableStock() + 1;
    }
}
