package com.magebyte.ddd.mall.payment.domain;

public interface OrderCheckout {
    record Checkout(String orderNo, long amountMinor, String currency) {}
    Checkout checkout(String orderNo);
}
