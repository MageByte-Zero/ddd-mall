package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Outbox 事件表数据对象（t_outbox_event）。
 *
 * <p>Outbox 模式的核心：领域事件在业务事务内以"一行数据"的形式和订单数据
 * 写入同一个库、同一个本地事务——要成一起成，要回滚一起回滚。事务提交后，
 * 中继器（{@code infrastructure.messaging.OutboxEventRelay}）再把这一行
 * 搬到 RocketMQ，搬成功才把 status 从 PENDING 改成 SENT。
 *
 * <p>只住在基础设施层：payload 是裸 JSON 字符串、status 是裸字符串，
 * 领域层不认识这张表，也不认识这个类。
 *
 * <p>status 取值：PENDING（待投递）/ SENT（已投递）/ FAILED（重试耗尽，
 * 需人工介入）。next_retry_at 为 null 表示立即可捞；失败退避后写入下次
 * 可捞时间，中继器只捞到期的行。
 */
@TableName("t_outbox_event")
public class OutboxEventDO {

    /** 待投递：事务已提交，等待中继器发到 MQ。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已投递：中继器已确认发到 MQ。 */
    public static final String STATUS_SENT = "SENT";
    /** 重试耗尽：超过最大重试次数仍失败，不再自动捞取，等待人工处理。 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 事件全局唯一 ID（= 领域事件 eventId，消息 keys），表内唯一。 */
    private String eventId;
    /** 聚合类型，如 Order。 */
    private String aggregateType;
    /** 聚合业务身份，订单场景为订单号。 */
    private String aggregateId;
    /** 事件名（= 消息 tag）：OrderCreated / OrderPaid…… */
    private String eventType;
    /** 事件消息体 JSON：与发往 MQ 的消息体逐字节一致。 */
    private String payload;
    private String status;
    private Integer retryCount;
    /** 下次可被中继器捞取的时间；null 表示立即可以捞。 */
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
    /** 投递成功时间；未成功前为 null。 */
    private LocalDateTime sentAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}
