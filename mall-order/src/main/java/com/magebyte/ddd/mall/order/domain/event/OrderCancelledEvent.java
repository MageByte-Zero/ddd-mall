package com.magebyte.ddd.mall.order.domain.event;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 订单已取消事件（OrderCancelled）：待支付订单被取消（用户主动 / 超时未支付），
 * 订单到达终态 CANCELLED。
 *
 * <p>由 {@code Order.cancel(...)} 抛出。载荷携带取消原因，
 * 库存订阅方据此释放预占库存（第 12 讲接入）。
 *
 * @param eventId        事件唯一 ID（UUID，发布时作为消息 keys）
 * @param eventName      事件名（线上 wire name），固定为 {@link #NAME}
 * @param schemaVersion  载荷 schema 版本，固定为 {@link #SCHEMA_VERSION}
 * @param orderNo        事件源订单号
 * @param reason         取消原因（用户主动取消 / 超时未支付自动取消）
 * @param operatedBy     取消操作人标识（如 user:1001、system:timeout-job）
 * @param occurredOn     事件发生时间（取消时间）
 */
public record OrderCancelledEvent(String eventId, String eventName, int schemaVersion,
                                  String orderNo, String reason, String operatedBy,
                                  LocalDateTime occurredOn) implements DomainEvent {

    /** 线上事件名 / 消息 tag：与第 1 讲锁定的领域事件词汇表一致。 */
    public static final String NAME = "OrderCancelled";

    /** 载荷 schema 版本，v1 为初版结构。 */
    public static final int SCHEMA_VERSION = 1;

    /** 聚合根迁移方法调用：事件 ID 在这里生成，事件名与版本固定。 */
    public static OrderCancelledEvent raise(String orderNo, String reason,
                                            String operatedBy, LocalDateTime when) {
        return new OrderCancelledEvent(UUID.randomUUID().toString(), NAME, SCHEMA_VERSION,
                orderNo, reason, operatedBy, when);
    }

    public OrderCancelledEvent {
        Objects.requireNonNull(eventId, "事件 ID 不能为空");
        Objects.requireNonNull(eventName, "事件名不能为空");
        Objects.requireNonNull(orderNo, "事件源订单号不能为空");
        Objects.requireNonNull(reason, "取消原因不能为空");
        Objects.requireNonNull(operatedBy, "取消操作人不能为空");
        Objects.requireNonNull(occurredOn, "事件发生时间不能为空");
    }
}
