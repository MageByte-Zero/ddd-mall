package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 订单状态历史数据对象（t_order_status_history）。
 *
 * <p>只住在基础设施层：状态是裸 String、时间是裸 LocalDateTime，
 * 与领域值对象 {@link com.magebyte.ddd.mall.order.domain.StatusChange}
 * 之间的翻译由 {@link OrderRepositoryImpl} 负责。历史只增不改，
 * 表没有 version / deleted 列。
 */
@TableName("t_order_status_history")
public class OrderStatusHistoryDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    /** 迁移前状态；创建记录为 null。 */
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String operatedBy;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOperatedBy() {
        return operatedBy;
    }

    public void setOperatedBy(String operatedBy) {
        this.operatedBy = operatedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
