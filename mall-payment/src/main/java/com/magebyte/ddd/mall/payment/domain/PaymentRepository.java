package com.magebyte.ddd.mall.payment.domain;

import java.util.Optional;

public interface PaymentRepository {
    Optional<Payment> find(String paymentNo);
    Optional<Payment> findByOrder(String orderNo);
    Optional<Payment> lock(String paymentNo);
    void insert(Payment payment);
    void confirm(Payment payment);
}
