package com.magebyte.ddd.mall.order.domain.event;

import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import com.magebyte.ddd.mall.order.domain.Refund;
import java.time.LocalDateTime;
import java.util.UUID;

/** 申请已登记，尚不能证明资金退回。 */
public record RefundRequestedEvent(String eventId, String eventName, int schemaVersion,
                         String orderNo, String refundNo, Money amount,
                         String reason, OrderStatus requestedFrom, LocalDateTime occurredOn) implements DomainEvent {
    public static final String NAME = "RefundRequested";
    public static final int SCHEMA_VERSION = 1;
    public static RefundRequestedEvent raise(String orderNo, Refund refund, LocalDateTime when) {
        return new RefundRequestedEvent(UUID.randomUUID().toString(), NAME, SCHEMA_VERSION,
                orderNo, refund.refundNo(), refund.amount(), refund.reason(), refund.requestedFrom(), when);
    }
}
