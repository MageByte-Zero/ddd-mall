package com.magebyte.ddd.mall.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** 支付确认后交给订单的公开事实，渠道流水仍归支付侧。 */
public record PaymentSucceeded(String eventId, String eventName, int schemaVersion, String orderNo,
                               String paymentNo, long amountMinor, String currency, Instant confirmedAt) {
    public static PaymentSucceeded from(Payment payment) {
        return new PaymentSucceeded(UUID.randomUUID().toString(), "PaymentSucceeded", 1,
                payment.orderNo(), payment.paymentNo(), payment.amount().minorUnits(),
                payment.amount().currency(), payment.confirmedAt());
    }
}
