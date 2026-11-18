package com.magebyte.ddd.mall.payment.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.payment.domain.Payment;
import com.magebyte.ddd.mall.payment.domain.PaymentJournal;
import com.magebyte.ddd.mall.payment.domain.PaymentSucceeded;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentJournal implements PaymentJournal {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcPaymentJournal(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void appendSuccess(PaymentSucceeded event) {
        jdbc.sql("INSERT INTO t_payment_outbox (payment_no, event_id, payload) VALUES (?, ?, ?)")
                .params(event.paymentNo(), event.eventId(), encode(event)).update();
    }

    @Override
    public void flagForReview(Payment.Observation observation, String reportedResult, String reason) {
        jdbc.sql("""
                INSERT INTO t_payment_callback_review
                (payment_no, claimed_order_no, channel_txn_no, reported_amount_minor, reported_currency,
                 reported_result, reason) VALUES (?, ?, ?, ?, ?, ?, ?)
                """).param(observation.paymentNo()).param(observation.orderNo()).param(observation.channelTxnNo())
                .param(observation.amountMinor()).param(observation.currency()).param(reportedResult).param(reason)
                .update();
    }

    private String encode(PaymentSucceeded event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存支付事实", e);
        }
    }
}
