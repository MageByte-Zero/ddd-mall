package com.magebyte.ddd.mall.order.domain.event;

import java.time.LocalDateTime;

/**
 * 领域事件（Domain Event）：领域中已经发生的业务事实。
 *
 * <p>判别标准：事件名是过去时（OrderCreated、OrderPaid……），表示"事实已经发生"，
 * 发布方不关心谁订阅、订阅方处理是否成功——它只负责把事实说出来。
 * 事件由聚合根在业务动作发生的那一刻抛出（{@code raise}），先收集在聚合内部；
 * 仓储保存聚合时，事件与业务数据在同一事务写入 outbox 表，事务提交后由中继器
 * 交给发布端口（{@link DomainEventPublisher}）发往消息中间件。
 *
 * <p>本接口是纯 Java：不依赖 Spring、Jackson、RocketMQ 任何框架，
 * 领域层零框架依赖由 ArchUnit 测试编译期守护。
 *
 * <p>事件身份用订单号（{@link #orderNo()}）而不是数据库自增 id：
 * 事件在聚合动作发生时就产生，此刻订单还没入库、自增 id 尚不存在；
 * 订单号是领域内生成的业务身份，出生即有、全局可识别。
 */
public interface DomainEvent {

    /**
     * 事件全局唯一 ID（UUID）。作为消息 keys 发往消息中间件，
     * 用于链路排查，也是后续讲次消费端幂等的依据。
     */
    String eventId();

    /**
     * 事件名（线上 wire name）：统一语言里的过去时事实，
     * 取值锁定在第 1 讲领域事件词汇表（OrderCreated / OrderPaid /
     * OrderShipped / OrderReceived / OrderCancelled），不自造名字。
     * 同时作为消息的 tag，订阅方可按事件名过滤。
     */
    String eventName();

    /**
     * 载荷 schema 版本：事件结构演进而字段不兼容时 +1。
     * 订阅方按版本反序列化，新老消费者可以共存。当前全部事件为 v1。
     */
    int schemaVersion();

    /** 事件源订单号：发生事实的那个订单聚合的业务身份。 */
    String orderNo();

    /** 事件发生时间：业务事实发生的时刻（操作时间），不是消息发送时刻。 */
    LocalDateTime occurredOn();
}
