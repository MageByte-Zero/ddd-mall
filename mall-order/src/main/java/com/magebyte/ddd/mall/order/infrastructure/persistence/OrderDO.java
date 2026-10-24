package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单数据对象（t_order）。
 *
 * <p>只住在基础设施层：字段贴着表结构长，金额是裸 BigDecimal、
 * 状态是裸 String、收货地址拆成三列——都是存储视角，不是领域视角。
 * 与聚合之间的翻译由 {@link OrderRepositoryImpl} 负责。
 */
@TableName("t_order")
public class OrderDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private String status;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private String refundNo;
    private BigDecimal refundAmount;
    private String refundReason;
    private String refundFromStatus;
    private String refundOutcome;
    private String refundReference;

    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    /** 乐观锁版本号，由 MyBatis-Plus 乐观锁插件维护。 */
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String value) { this.refundNo = value; }

    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal value) { this.refundAmount = value; }

    public String getRefundReason() { return refundReason; }
    public void setRefundReason(String value) { this.refundReason = value; }

    public String getRefundFromStatus() { return refundFromStatus; }
    public void setRefundFromStatus(String value) { this.refundFromStatus = value; }

    public String getRefundOutcome() { return refundOutcome; }
    public void setRefundOutcome(String value) { this.refundOutcome = value; }

    public String getRefundReference() { return refundReference; }
    public void setRefundReference(String value) { this.refundReference = value; }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = receiverPhone;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public void setReceiverAddress(String receiverAddress) {
        this.receiverAddress = receiverAddress;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
