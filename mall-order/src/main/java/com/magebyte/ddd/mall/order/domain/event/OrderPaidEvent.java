package com.magebyte.ddd.mall.order.domain.event;

import com.magebyte.ddd.mall.order.domain.Money;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 订单已支付事件（OrderPaid）：支付回调成功，订单从待支付迁移到已支付。
 *
 * <p>由 {@code Order.markPaid(...)} 抛出。载荷携带实付金额，
 * 库存、营销等订阅方据此推进各自流程（后续讲次接入）。
 *
 * @param eventId        事件唯一 ID（UUID，发布时作为消息 keys）
 * @param eventName      事件名（线上 wire name），固定为 {@link #NAME}
 * @param schemaVersion  载荷 schema 版本，固定为 {@link #SCHEMA_VERSION}
 * @param orderNo        事件源订单号
 * @param paidAmount     实付金额（与订单应付金额相等，金额守恒在聚合内已校验）
 * @param occurredOn     事件发生时间（支付回调时间）
 */
public record OrderPaidEvent(String eventId, String eventName, int schemaVersion,
                             String orderNo, Money paidAmount, LocalDateTime occurredOn)
        implements DomainEvent {

    /** 线上事件名 / 消息 tag：与第 1 讲锁定的领域事件词汇表一致。 */
    public static final String NAME = "OrderPaid";

    /** 载荷 schema 版本，v1 为初版结构。 */
    public static final int SCHEMA_VERSION = 1;

    /** 聚合根迁移方法调用：事件 ID 在这里生成，事件名与版本固定。 */
    public static OrderPaidEvent raise(String orderNo, Money paidAmount, LocalDateTime when) {
        return new OrderPaidEvent(UUID.randomUUID().toString(), NAME, SCHEMA_VERSION,
                orderNo, paidAmount, when);
    }

    public OrderPaidEvent {
        Objects.requireNonNull(eventId, "事件 ID 不能为空");
        Objects.requireNonNull(eventName, "事件名不能为空");
        Objects.requireNonNull(orderNo, "事件源订单号不能为空");
        Objects.requireNonNull(paidAmount, "实付金额不能为空");
        Objects.requireNonNull(occurredOn, "事件发生时间不能为空");
    }
}
