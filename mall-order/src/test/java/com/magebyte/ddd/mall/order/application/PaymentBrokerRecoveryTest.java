package com.magebyte.ddd.mall.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.commons.security.MessageAuthentication;
import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.interfaces.messaging.PaymentSucceededConsumer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "ddd.payment.new-orders-enabled=true", "ddd.payment.consumer-enabled=true",
        "ddd.payment.consumer-group=ddd17-order-broker-verification", "ddd.payment.handoff-token=fixture-only",
        "ddd.payment.events-topic=payment-events", "ddd.order.event-logger-enabled=false"
})
class PaymentBrokerRecoveryTest {
    private static final String SECRET = "fixture-only";
    @Autowired OrderRepository orders;
    @Autowired TransactionTemplate transactions;
    @Autowired RocketMQTemplate broker;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AcceptPaymentService acceptance;
    @SpyBean PaymentSucceededConsumer consumer;
    private String orderNo;

    @AfterEach void quarantineOwnOutbox() {
        if (orderNo != null) {
            jdbc.update("UPDATE t_outbox_event SET status = 'FAILED' WHERE aggregate_id = ? AND status = 'PENDING'",
                    orderNo);
        }
    }

    @Test void broker_retries_after_order_commit_without_reapplying_the_business_fact() throws Exception {
        Order order = Order.create(17L, new Address("合成用户", "13800000000", "合成地址"));
        order.addItem(OrderItem.create(17L, 17L, "两件商品", 2, Money.of("50")));
        orderNo = order.orderNo();
        transactions.executeWithoutResult(transaction -> orders.save(order));
        var fact = new AcceptPaymentService.PaymentFact(UUID.randomUUID().toString(), "PaymentSucceeded",
                1, order.orderNo(), UUID.randomUUID().toString(), 10000, "CNY", Instant.now());
        String payload = json.writeValueAsString(fact);
        String envelope = json.writeValueAsString(Map.of("payload", payload,
                "signature", MessageAuthentication.sign(SECRET, payload)));

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(
                json.writeValueAsString(Map.of("payload", payload, "signature", "wrong"))));
        assertTrue(acceptance.findOutcome(fact.paymentNo()).isEmpty());

        var deliveries = new AtomicInteger();
        var brokerRetried = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (deliveries.incrementAndGet() == 1) {
                assertEquals("ACCEPTED", acceptance.findOutcome(fact.paymentNo()).orElseThrow().outcome());
                throw new IllegalStateException("测试：订单提交后、MQ 确认前发生故障");
            }
            brokerRetried.countDown();
            return null;
        }).when(consumer).onMessage(argThat(envelope::equals));

        assertEquals(SendStatus.SEND_OK, broker.syncSend("payment-events:PaymentSucceeded", envelope).getSendStatus());
        assertTrue(brokerRetried.await(35, TimeUnit.SECONDS), "应由真实 Broker 重投");
        assertEquals(2, deliveries.get());

        var newEnvelopeSameFact = new AcceptPaymentService.PaymentFact(UUID.randomUUID().toString(), "PaymentSucceeded",
                1, fact.orderNo(), fact.paymentNo(), 10000, "CNY", fact.confirmedAt());
        acceptance.accept(newEnvelopeSameFact);
        var conflictingReplay = new AcceptPaymentService.PaymentFact(fact.eventId(), "PaymentSucceeded",
                1, fact.orderNo(), fact.paymentNo(), 9900, "CNY", fact.confirmedAt());
        assertThrows(OrderDomainException.class, () -> acceptance.accept(conflictingReplay));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id = ? AND event_type = 'OrderPaid'
                """, Integer.class, fact.orderNo()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_payment_inbox WHERE payment_no = ?",
                Integer.class, fact.paymentNo()));
        System.out.println("PAYMENT_MQ ackFailureAfterCommit=true brokerDeliveries=2 orderPaidFacts=1 distinctEnvelopeInboxRows=2");
    }
}
