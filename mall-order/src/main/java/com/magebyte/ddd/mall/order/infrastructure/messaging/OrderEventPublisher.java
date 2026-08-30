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

/**
 * 领域事件发布端口的 RocketMQ 适配器（基础设施层）。
 *
 * <p>领域层只认识 {@link DomainEventPublisher} 端口；本类负责把事件
 * 翻译成 RocketMQ 消息：统一 topic {@link #TOPIC}，tag = 事件名
 * （订阅方可按 tag 只订阅自己关心的事件），消息体 = 事件 record 的 JSON，
 * keys = 事件 ID（按事件排查链路、后续讲次消费端幂等的依据）。
 *
 * <p>本类只负责"发"。"什么时候发"由仓储决定：必须在数据库事务提交之后
 * （afterCommit），事务回滚则一批事件整批丢弃——避免"库里没这笔订单、
 * 消息却已经发出去"的幽灵事件。
 */
@Component
public class OrderEventPublisher implements DomainEventPublisher {

    /** 订单领域事件统一 topic；事件名以 tag 区分。 */
    public static final String TOPIC = "order-events";

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final RocketMQTemplate rocketMQTemplate;

    public OrderEventPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        for (DomainEvent event : events) {
            // RocketMQ destination 语法：topic:tag
            String destination = TOPIC + ":" + event.eventName();
            Message<DomainEvent> message = MessageBuilder.withPayload(event)
                    .setHeader(RocketMQHeaders.KEYS, event.eventId())
                    .build();
            rocketMQTemplate.syncSend(destination, message);
            log.info("领域事件已发布: name={}, eventId={}, orderNo={}, destination={}",
                    event.eventName(), event.eventId(), event.orderNo(), destination);
        }
    }
}
