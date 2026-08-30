package com.magebyte.ddd.mall.order.domain.event;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 订单已收货事件（OrderReceived）：用户确认收货，订单到达终态 RECEIVED。
 *
 * <p>由 {@code Order.confirmReceived(...)} 抛出。
 *
 * @param eventId        事件唯一 ID（UUID，发布时作为消息 keys）
 * @param eventName      事件名（线上 wire name），固定为 {@link #NAME}
 * @param schemaVersion  载荷 schema 版本，固定为 {@link #SCHEMA_VERSION}
 * @param orderNo        事件源订单号
 * @param operatedBy     收货操作人标识（如 user:1001）
 * @param occurredOn     事件发生时间（确认收货时间）
 */
public record OrderReceivedEvent(String eventId, String eventName, int schemaVersion,
                                 String orderNo, String operatedBy, LocalDateTime occurredOn)
        implements DomainEvent {

    /** 线上事件名 / 消息 tag：与第 1 讲锁定的领域事件词汇表一致。 */
    public static final String NAME = "OrderReceived";

    /** 载荷 schema 版本，v1 为初版结构。 */
    public static final int SCHEMA_VERSION = 1;

    /** 聚合根迁移方法调用：事件 ID 在这里生成，事件名与版本固定。 */
    public static OrderReceivedEvent raise(String orderNo, String operatedBy, LocalDateTime when) {
        return new OrderReceivedEvent(UUID.randomUUID().toString(), NAME, SCHEMA_VERSION,
                orderNo, operatedBy, when);
    }

    public OrderReceivedEvent {
        Objects.requireNonNull(eventId, "事件 ID 不能为空");
        Objects.requireNonNull(eventName, "事件名不能为空");
        Objects.requireNonNull(orderNo, "事件源订单号不能为空");
        Objects.requireNonNull(operatedBy, "收货操作人不能为空");
        Objects.requireNonNull(occurredOn, "事件发生时间不能为空");
    }
}
