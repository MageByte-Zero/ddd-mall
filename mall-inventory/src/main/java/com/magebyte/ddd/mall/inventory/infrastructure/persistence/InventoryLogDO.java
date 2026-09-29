package com.magebyte.ddd.mall.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 库存流水（t_inventory_log）：每一次库存变动的前后快照。
 *
 * <p>为什么要有它。三字段守恒只是让"当前状态"说得通，它回答不了"这 7 件是什么时候
 * 被谁占走的"。超卖这类事故最难受的地方从来不是"数据错了"，而是<b>查不出是哪一步错的</b>：
 * 库存少了 3 件，日志里只有一堆 UPDATE，你不知道是预占没释放、还是释放被重复执行了。
 * 有了流水，守恒式里每一次挪动都有据可查。
 *
 * <p>它只住在基础设施层：<b>不是聚合的一部分</b>。聚合关心的是"现在这三个数是多少"，
 * 流水是"历史上发生过什么"，属于审计需求。把它塞进 {@code Inventory} 聚合里会让聚合
 * 随订单量无限膨胀，每次预占都要加载几百条历史——这是聚合设计里最典型的过度包含。
 *
 * <p>第 13 讲引入幂等键后会在本表补业务单号列，用于回答"这一笔预占是哪个订单的"。
 */
@TableName("t_inventory_log")
public class InventoryLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String skuCode;
    /** 变动类型：RESERVE / CONFIRM / RELEASE / RESTOCK。 */
    private String changeType;
    private Integer quantity;
    private Integer totalBefore;
    private Integer totalAfter;
    private Integer availableBefore;
    private Integer availableAfter;
    private Integer reservedBefore;
    private Integer reservedAfter;
    private LocalDateTime createdAt;

    public static InventoryLogDO of(String skuCode, String changeType, int quantity,
                                    int totalBefore, int totalAfter,
                                    int availableBefore, int availableAfter,
                                    int reservedBefore, int reservedAfter) {
        InventoryLogDO log = new InventoryLogDO();
        log.skuCode = skuCode;
        log.changeType = changeType;
        log.quantity = quantity;
        log.totalBefore = totalBefore;
        log.totalAfter = totalAfter;
        log.availableBefore = availableBefore;
        log.availableAfter = availableAfter;
        log.reservedBefore = reservedBefore;
        log.reservedAfter = reservedAfter;
        log.createdAt = LocalDateTime.now();
        return log;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Integer getTotalBefore() {
        return totalBefore;
    }

    public void setTotalBefore(Integer totalBefore) {
        this.totalBefore = totalBefore;
    }

    public Integer getTotalAfter() {
        return totalAfter;
    }

    public void setTotalAfter(Integer totalAfter) {
        this.totalAfter = totalAfter;
    }

    public Integer getAvailableBefore() {
        return availableBefore;
    }

    public void setAvailableBefore(Integer availableBefore) {
        this.availableBefore = availableBefore;
    }

    public Integer getAvailableAfter() {
        return availableAfter;
    }

    public void setAvailableAfter(Integer availableAfter) {
        this.availableAfter = availableAfter;
    }

    public Integer getReservedBefore() {
        return reservedBefore;
    }

    public void setReservedBefore(Integer reservedBefore) {
        this.reservedBefore = reservedBefore;
    }

    public Integer getReservedAfter() {
        return reservedAfter;
    }

    public void setReservedAfter(Integer reservedAfter) {
        this.reservedAfter = reservedAfter;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
