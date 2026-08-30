package com.magebyte.ddd.mall.order.domain.event;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 订单已创建事件（OrderCreated）：用户下单成功，订单进入待支付状态。
 *
 * <p>由 {@code Order.create(...)} 在订单出生时抛出，是订单生命周期的第一个事实。
 *
 * <p>{@code eventName} 与 {@code schemaVersion} 是事件信封字段（所有事件共有）：
 * 消息体必须自包含"我是什么事件、第几版 schema"，订阅方只看消息体也能识别类型，
 * 不依赖消息 tag。
 *
 * @param eventId        事件唯一 ID（UUID，发布时作为消息 keys）
 * @param eventName      事件名（线上 wire name），固定为 {@link #NAME}
 * @param schemaVersion  载荷 schema 版本，固定为 {@link #SCHEMA_VERSION}
 * @param orderNo        事件源订单号
 * @param userId         下单用户 ID
 * @param occurredOn     事件发生时间（下单时间）
 */
public record OrderCreatedEvent(String eventId, String eventName, int schemaVersion,
                                String orderNo, Long userId, LocalDateTime occurredOn)
        implements DomainEvent {

    /** 线上事件名 / 消息 tag：与第 1 讲锁定的领域事件词汇表一致。 */
    public static final String NAME = "OrderCreated";

    /** 载荷 schema 版本，v1 为初版结构。 */
    public static final int SCHEMA_VERSION = 1;

    /** 聚合根工厂调用：事件 ID 在这里生成，事件名与版本固定。 */
    public static OrderCreatedEvent raise(String orderNo, Long userId, LocalDateTime when) {
        return new OrderCreatedEvent(UUID.randomUUID().toString(), NAME, SCHEMA_VERSION,
                orderNo, userId, when);
    }

    public OrderCreatedEvent {
        Objects.requireNonNull(eventId, "事件 ID 不能为空");
        Objects.requireNonNull(eventName, "事件名不能为空");
        Objects.requireNonNull(orderNo, "事件源订单号不能为空");
        Objects.requireNonNull(userId, "下单用户 ID 不能为空");
        Objects.requireNonNull(occurredOn, "事件发生时间不能为空");
    }
}
