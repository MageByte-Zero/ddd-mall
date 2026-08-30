package com.magebyte.ddd.mall.order.infrastructure.messaging;

import java.time.Instant;

/**
 * 日志消费者收到的一条订单事件（教学/测试观察用）。
 *
 * @param tag        消息 tag，即事件名（OrderCreated / OrderPaid / …）
 * @param keys       消息 keys，即事件 ID（eventId），用于链路排查
 * @param jsonBody   消息体原文（事件 record 序列化后的 JSON）
 * @param receivedAt 消费者收到该消息的时刻
 */
public record ReceivedOrderEvent(String tag, String keys, String jsonBody,
                                 Instant receivedAt) {
}
