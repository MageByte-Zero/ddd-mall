package com.magebyte.ddd.mall.order.infrastructure.messaging;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 订单事件最小消费者：订阅 order-events 的全量 tag（selectorExpression = "*"），
 * 收到事件后打日志，并把原文存入 {@link DomainEventSink} 供观察与测试断言。
 *
 * <p>这是本讲交付的"Consumer 端最小示例"——证明领域事件真的从聚合根
 * 一路到达了订阅方。库存预占释放、支付状态联动等真实业务处理，
 * 由后续讲次各限界上下文自己的消费者承担，本类届时退役。
 */
@Component
@RocketMQMessageListener(
        topic = OrderEventPublisher.TOPIC,
        consumerGroup = "mall-order-event-logger",
        selectorExpression = "*")
public class OrderEventLoggerConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(OrderEventLoggerConsumer.class);

    private final DomainEventSink sink;

    public OrderEventLoggerConsumer(DomainEventSink sink) {
        this.sink = sink;
    }

    @Override
    public void onMessage(MessageExt message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("收到领域事件: tag={}, keys={}, queueId={}, body={}",
                message.getTags(), message.getKeys(), message.getQueueId(), body);
        sink.offer(new ReceivedOrderEvent(
                message.getTags(), message.getKeys(), body, Instant.now()));
    }
}
