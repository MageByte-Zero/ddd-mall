package com.magebyte.ddd.mall.payment.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.commons.security.MessageAuthentication;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PaymentOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxRelay.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final RocketMQTemplate broker;
    private final String topic;
    private final String key;

    public PaymentOutboxRelay(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json,
                              RocketMQTemplate broker,
                              @Value("${ddd.payment.events-topic:payment-events}") String topic,
                              @Value("${ddd.payment.handoff-token:}") String key) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.broker = broker;
        this.topic = topic;
        this.key = key;
    }

    @Scheduled(fixedDelayString = "${ddd.payment.relay-interval-ms:2000}",
               initialDelayString = "${ddd.payment.relay-initial-delay-ms:5000}")
    public void relay() {
        jdbc.sql("""
                SELECT payment_no FROM t_payment_outbox WHERE status = 'PENDING'
                AND next_attempt_at <= CURRENT_TIMESTAMP(6) ORDER BY next_attempt_at LIMIT 20
                """).query(String.class).list().forEach(paymentNo -> {
            try {
                transactions.executeWithoutResult(tx -> deliver(paymentNo));
            } catch (RuntimeException e) {
                log.warn("支付中继事务未完成，稍后重试 paymentNo={} cause={}", paymentNo, e.getClass().getSimpleName());
            }
        });
    }

    public String status(String paymentNo) {
        return jdbc.sql("SELECT status FROM t_payment_outbox WHERE payment_no = ?")
                .param(paymentNo).query(String.class).optional().orElse(null);
    }

    public boolean requeue(String paymentNo) {
        return jdbc.sql("""
                UPDATE t_payment_outbox SET status = 'PENDING', attempts = 0,
                next_attempt_at = CURRENT_TIMESTAMP(6), last_error = NULL WHERE payment_no = ?
                """).param(paymentNo).update() == 1;
    }

    private record Delivery(String eventId, String payload, int attempts) {}

    private void deliver(String paymentNo) {
        var pending = jdbc.sql("""
                SELECT event_id, payload, attempts FROM t_payment_outbox WHERE payment_no = ?
                AND status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6) FOR UPDATE
                """).param(paymentNo).query((rs, row) -> new Delivery(
                rs.getString("event_id"), rs.getString("payload"), rs.getInt("attempts"))).optional();
        if (pending.isEmpty()) return;
        Delivery delivery = pending.get();
        try {
            String body = json.writeValueAsString(Map.of("payload", delivery.payload(),
                    "signature", MessageAuthentication.sign(key, delivery.payload())));
            var message = MessageBuilder.withPayload(body).setHeader(RocketMQHeaders.KEYS, delivery.eventId()).build();
            if (broker.syncSend(topic + ":PaymentSucceeded", message).getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker 未确认支付事实");
            }
        } catch (RuntimeException | JsonProcessingException e) {
            int attempts = delivery.attempts() + 1;
            String failure = e.getClass().getSimpleName();
            jdbc.sql("""
                    UPDATE t_payment_outbox SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ?
                    WHERE payment_no = ?
                    """).params(attempts >= 5 ? "FAILED" : "PENDING", attempts,
                    Timestamp.from(Instant.now().plusSeconds(5L << (attempts - 1))), failure, paymentNo)
                    .update();
            log.warn("支付事实投递失败 paymentNo={} eventId={} attempts={} cause={}",
                    paymentNo, delivery.eventId(), attempts, failure);
            return;
        }
        // Broker 确认后才标记，标记失败时相同事件可以被重新投递。
        jdbc.sql("UPDATE t_payment_outbox SET status = 'SENT', last_error = NULL WHERE payment_no = ?")
                .param(paymentNo).update();
    }
}
