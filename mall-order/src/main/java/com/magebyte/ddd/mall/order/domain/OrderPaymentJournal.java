package com.magebyte.ddd.mall.order.domain;

import java.math.BigDecimal;
import java.util.Optional;

/** 订单侧收款交接的持久化边界。 */
public interface OrderPaymentJournal {
    record Checkout(String orderNo, boolean managed, OrderStatus status, BigDecimal total) {}
    record Acceptance(String paymentNo, String orderNo, String outcome, String fingerprint) {}
    record Inbox(String paymentNo, String fingerprint) {}

    Checkout lock(String orderNo);
    Optional<Acceptance> acceptance(String paymentNo);
    Optional<Inbox> inbox(String eventId);
    void appendAcceptance(String paymentNo, String orderNo, String fingerprint, String outcome);
    void appendInbox(String eventId, String paymentNo, String fingerprint);
}
