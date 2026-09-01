package com.magebyte.ddd.mall.order.domain.event;

import java.util.List;

/**
 * 领域事件发布端口（Domain Event Publisher Port）。
 *
 * <p>依赖倒置的落点：领域层只定义"事件要发出去"这个意图，
 * 不认识 RocketMQ、Kafka、Spring 任何技术；适配器住基础设施层
 * （{@code infrastructure.messaging.OrderEventPublisher}），
 * 由 Spring 容器把实现注入给仓储。
 *
 * <p>一次发布一批事件：一次事务里聚合可能产生多个事实
 * （本讲一个聚合一次保存只对应一个事件，批量签名为后续讲次预留），
 * 发布方保证同一批事件的发送顺序与列表顺序一致。
 *
 * <p>第 9 讲起调用方是 Outbox 中继器（基础设施层）：事件先在业务事务内
 * 写入 outbox 表，事务提交后中继器逐行取出、调用本端口投递。因此到达
 * 本方法的事件必然来自已提交事务；中继器在发送失败时会保留 outbox 行
 * 并退避重试，事件不丢（代价是可能重复投递，去重由消费端幂等负责）。
 */
public interface DomainEventPublisher {

    /**
     * 发布一批领域事件。调用方（Outbox 中继器）保证只投递已提交事务的事件；
     * 事务回滚时事件随 outbox 行一起回滚，任何订阅方都不该收到（无幽灵事件）。
     */
    void publishAll(List<DomainEvent> events);
}
