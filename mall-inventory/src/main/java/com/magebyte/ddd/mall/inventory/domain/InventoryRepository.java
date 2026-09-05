package com.magebyte.ddd.mall.inventory.domain;

import java.util.Optional;

/**
 * 库存仓储端口：领域层定义，基础设施层实现（依赖倒置）。
 */
public interface InventoryRepository {

    /** 按 SKU 编码查找库存聚合。 */
    Optional<Inventory> findBySkuCode(String skuCode);

    /**
     * 条件扣减：{@code available_stock = available_stock - quantity}，
     * 且仅当当前可售库存 ≥ quantity 时命中（返回影响行数 1）。
     * 影响行数为 0 表示并发/库存不足，调用方按库存不足处理。
     *
     * @return 实际影响行数（0 或 1）
     */
    int deduct(String skuCode, int quantity);
}
