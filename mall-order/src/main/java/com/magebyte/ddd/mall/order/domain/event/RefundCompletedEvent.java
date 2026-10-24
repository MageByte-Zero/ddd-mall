package com.magebyte.ddd.mall.order.domain.event;

import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Refund;
import java.time.LocalDateTime;
import java.util.UUID;

/** 订单已根据确定成功结果记账，不包含库存完成承诺。 */
public record RefundCompletedEvent(String eventId, String eventName, int schemaVersion,
                         String orderNo, String refundNo, Money amount,
                         String reference, LocalDateTime occurredOn) implements DomainEvent {
    public static final String NAME = "RefundCompleted";
    public static final int SCHEMA_VERSION = 1;
    public static RefundCompletedEvent raise(String orderNo, Refund refund, LocalDateTime when) {
        return new RefundCompletedEvent(UUID.randomUUID().toString(), NAME, SCHEMA_VERSION,
                orderNo, refund.refundNo(), refund.amount(), refund.result().reference(), when);
    }
}
