package com.magebyte.ddd.mall.payment.application;

import com.magebyte.ddd.mall.payment.domain.Payment;
import com.magebyte.ddd.mall.payment.domain.PaymentAmount;
import com.magebyte.ddd.mall.payment.domain.PaymentRepository;
import com.magebyte.ddd.mall.payment.domain.OrderCheckout;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class PaymentApplicationService {
    private final PaymentRepository payments;
    private final OrderCheckout orders;

    public PaymentApplicationService(PaymentRepository payments, OrderCheckout orders) {
        this.payments = payments;
        this.orders = orders;
    }

    public Payment create(String orderNo) {
        return payments.findByOrder(orderNo).orElseGet(() -> open(orderNo));
    }

    private Payment open(String orderNo) {
        OrderCheckout.Checkout checkout = orders.checkout(orderNo);
        Payment payment = new Payment(UUID.randomUUID().toString(), orderNo,
                new PaymentAmount(checkout.amountMinor(), checkout.currency()), "SIMULATED", null, null);
        try {
            payments.insert(payment);
            return payment;
        } catch (DuplicateKeyException conflict) {
            return payments.findByOrder(orderNo).orElseThrow(() -> conflict);
        }
    }
}
