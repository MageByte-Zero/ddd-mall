package com.magebyte.ddd.mall.order.infrastructure.messaging;

import com.magebyte.ddd.mall.order.domain.event.DomainEvent;
import com.magebyte.ddd.mall.order.domain.event.DomainEventPublisher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;

/**
 * 领域事件发布端口的 RocketMQ 适配器（基础设施层）。
 *
 * <p>领域层只认识 {@link DomainEventPublisher} 端口；本类负责把事件
 * 翻译成 RocketMQ 消息：统一 topic {@link #TOPIC}，tag = 事件名
 * （订阅方可按 tag 只订阅自己关心的事件），消息体 = 事件 record 的 JSON，
 * keys = 事件 ID（按事件排查链路、后续讲次消费端幂等的依据）。
 *
 * <p>本类只负责"怎么发"。"什么时候发"在第 9 讲起由 Outbox 中继器
 * （{@link OutboxEventRelay}）决定：仓储在业务事务内把事件写进
 * t_outbox_event，事务提交后中继器逐行捞出、调用本端口投递，
 * 发成功才把行标记为 SENT。中继经当前读与全局锁检查后才调用本方法，
 * 回滚的事务连 outbox 行一起回滚，幽灵事件在更上游被堵住。
 */
@Component
public class OrderEventPublisher implements DomainEventPublisher {

    /** 订单领域事件统一 topic；事件名以 tag 区分。 */
    public static final String TOPIC = "order-events";

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    @org.springframework.beans.factory.annotation.Value("${ddd.order.events-topic:order-events}")
    private String topic = TOPIC;

    private final RocketMQTemplate rocketMQTemplate;

    public OrderEventPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        for (DomainEvent event : events) {
            // RocketMQ destination 语法：topic:tag
            String destination = topic + ":" + event.eventName();
            Message<DomainEvent> message = MessageBuilder.withPayload(event)
                    .setHeader(RocketMQHeaders.KEYS, event.eventId())
                    .build();
            SendResult result = rocketMQTemplate.syncSend(destination, message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker 未确认 SEND_OK: " + result);
            }
            log.info("领域事件已发布: name={}, eventId={}, orderNo={}, destination={}",
                    event.eventName(), event.eventId(), event.orderNo(), destination);
        }
    }
}
