package com.magebyte.ddd.mall.inventory.application;

import com.magebyte.ddd.mall.inventory.domain.Inventory;
import com.magebyte.ddd.mall.inventory.domain.InventoryDomainException;
import com.magebyte.ddd.mall.inventory.domain.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存应用服务：用例编排，不含业务规则（规则在 {@link Inventory} 聚合里）。
 *
 * <p>{@code deduct} 是 Seata AT 分支事务的边界：订单 BC 的全局事务经
 * Feign 调到这里时，当前线程已绑定全局事务 XID（HTTP 头 TX_XID 传播），
 * 本方法上的 {@link Transactional} 本地事务会被 Seata 数据源代理登记为
 * 一个分支事务——一阶段随本地提交上报 TC，二阶段由 TC 通知提交或回滚。
 */
@Service
public class InventoryApplicationService {

    private final InventoryRepository inventoryRepository;

    public InventoryApplicationService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * 扣减库存。
     *
     * @param skuCode  SKU 编码
     * @param quantity 扣减数量（必须为正）
     */
    @Transactional
    public void deduct(String skuCode, int quantity) {
        Inventory inventory = inventoryRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new InventoryDomainException("库存记录不存在: " + skuCode));
        // 领域守卫：库存不足在这里抛出，异常沿 HTTP 响应回到订单 BC，
        // 触发订单侧全局事务回滚
        inventory.deduct(quantity);
        int affected = inventoryRepository.deduct(skuCode, quantity);
        if (affected == 0) {
            // 并发窗口：读到时还够、更新时已不够——条件 UPDATE 兜底
            throw new InventoryDomainException("库存不足（并发扣减冲突）: " + skuCode);
        }
    }
}
