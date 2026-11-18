package com.magebyte.ddd.mall.payment.domain;

/** 金额用整数分，支付上下文负责币种约束。 */
public record PaymentAmount(long minorUnits, String currency) {
    public PaymentAmount {
        if (minorUnits <= 0 || !"CNY".equals(currency)) {
            throw new IllegalArgumentException("支付金额必须为正数 CNY");
        }
    }
}
