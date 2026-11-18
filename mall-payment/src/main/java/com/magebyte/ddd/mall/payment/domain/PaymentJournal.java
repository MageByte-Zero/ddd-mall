package com.magebyte.ddd.mall.payment.domain;

public interface PaymentJournal {
    void appendSuccess(PaymentSucceeded event);
    void flagForReview(Payment.Observation observation, String reportedResult, String reason);
}
