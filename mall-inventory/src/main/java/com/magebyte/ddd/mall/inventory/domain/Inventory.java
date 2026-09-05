package com.magebyte.ddd.mall.inventory.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 库存（Inventory）聚合根。
 *
 * <p>本讲（Seata AT 跨 BC 事务）只引入最小库存模型：一个 SKU 一行、一个可售库存数、
 * 一条 {@link #deduct(int)} 扣减守卫。预占/释放、库存流水、乐观锁防超卖
 * 在库存预占讲次重写本聚合（预占库存 + 可售库存双字段语义 + 乐观锁）。
 *
 * <p>守护的不变量（订单需求文档第 4 节"库存守恒"的最小子集）：
 * <ul>
 *   <li>扣减数量必须为正；</li>
 *   <li>可售库存不得扣成负数——库存不足直接拒绝。</li>
 * </ul>
 */
public class Inventory {

    private Long id;
    private final String skuCode;
    private final String skuName;
    private final int totalStock;
    private int availableStock;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Inventory(String skuCode, String skuName, int totalStock, int availableStock) {
        this.skuCode = skuCode;
        this.skuName = skuName;
        this.totalStock = totalStock;
        this.availableStock = availableStock;
    }

    /** 仓储重组入口：从持久化数据还原聚合。 */
    public static Inventory reconstitute(Long id, String skuCode, String skuName,
                                         int totalStock, int availableStock,
                                         LocalDateTime createdAt, LocalDateTime updatedAt) {
        Objects.requireNonNull(id, "持久化库存的 id 不能为空");
        Objects.requireNonNull(skuCode, "SKU 编码不能为空");
        if (totalStock < 0 || availableStock < 0 || availableStock > totalStock) {
            throw new InventoryDomainException("持久化数据违反库存守恒：总库存 " + totalStock
                    + "，可售库存 " + availableStock);
        }
        Inventory inventory = new Inventory(skuCode, skuName, totalStock, availableStock);
        inventory.id = id;
        inventory.createdAt = createdAt;
        inventory.updatedAt = updatedAt;
        return inventory;
    }

    /**
     * 扣减可售库存。库存不足或数量非法时拒绝——
     * 这是库存侧分支事务失败、进而触发全局事务回滚的领域入口。
     */
    public void deduct(int quantity) {
        if (quantity <= 0) {
            throw new InventoryDomainException("扣减数量必须为正数：" + quantity);
        }
        if (quantity > availableStock) {
            throw new InventoryDomainException("库存不足：SKU=" + skuCode
                    + "，可售 " + availableStock + "，请求扣减 " + quantity);
        }
        this.availableStock -= quantity;
        this.updatedAt = LocalDateTime.now();
    }

    public Long id() {
        return id;
    }

    public String skuCode() {
        return skuCode;
    }

    public String skuName() {
        return skuName;
    }

    public int totalStock() {
        return totalStock;
    }

    public int availableStock() {
        return availableStock;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }
}
