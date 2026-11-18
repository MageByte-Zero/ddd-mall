package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderNotFoundException;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.seata.tm.api.GlobalTransaction;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"seata.enabled=true", "ddd.payment.new-orders-enabled=true",
        "ddd.order.event-logger-enabled=false"})
class PaymentAtWindowTest {
    @Autowired OrderRepository orders;
    @Autowired AcceptPaymentService payments;
    @Autowired TransactionTemplate local;
    @Autowired JdbcTemplate jdbc;
    private final List<String> ownOrders = new ArrayList<>();

    @AfterEach void quarantineOwnOutbox() {
        for (String orderNo : ownOrders) {
            jdbc.update("UPDATE t_outbox_event SET status = 'FAILED' WHERE aggregate_id = ? AND status = 'PENDING'",
                    orderNo);
        }
    }

    @Test void checkout_waits_for_final_result_of_order_creation() throws Exception {
        for (boolean commit : new boolean[]{true, false}) {
            Order order = newOrder();
            GlobalTransaction transaction = begin();
            try {
                local.executeWithoutResult(status -> orders.save(order));
                assertEquals("PENDING_PAY", independent(() -> status(order.orderNo())));
                assertGlobalLockConflict(() -> payments.checkout(order.orderNo()));
            } catch (Throwable failure) {
                transaction.rollback();
                throw failure;
            }
            if (commit) {
                transaction.commit();
                assertEquals(10000L, payments.checkout(order.orderNo()).amountMinor());
            } else {
                transaction.rollback();
                assertThrows(OrderNotFoundException.class, () -> payments.checkout(order.orderNo()));
            }
            System.out.printf("PAYMENT_AT create phase1=PENDING_PAY checkout=blocked final=%s%n",
                    commit ? "eligible" : "absent");
        }
    }

    @Test void handoff_waits_for_final_result_of_order_cancellation() throws Exception {
        for (boolean commit : new boolean[]{true, false}) {
            Order order = newOrder();
            local.executeWithoutResult(status -> orders.save(order));
            var fact = fact(order.orderNo());
            GlobalTransaction transaction = begin();
            try {
                local.executeWithoutResult(status -> {
                    Order loaded = orders.findByOrderNo(order.orderNo()).orElseThrow();
                    loaded.cancel("合成测试", "fixture", LocalDateTime.now());
                    orders.save(loaded);
                });
                assertEquals("CANCELLED", independent(() -> status(order.orderNo())));
                assertGlobalLockConflict(() -> { payments.accept(fact); return null; });
                assertTrue(independent(() -> payments.findOutcome(fact.paymentNo()).isEmpty()));
            } catch (Throwable failure) {
                transaction.rollback();
                throw failure;
            }
            if (commit) transaction.commit();
            else transaction.rollback();

            payments.accept(fact);
            var outcome = payments.findOutcome(fact.paymentNo()).orElseThrow();
            assertEquals(commit ? "REVIEW_CANCELLED_ORDER" : "ACCEPTED", outcome.outcome());
            assertEquals(commit ? OrderStatus.CANCELLED : OrderStatus.PAID,
                    orders.findByOrderNo(order.orderNo()).orElseThrow().status());
            assertEquals(commit ? 0 : 1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id = ? AND event_type = 'OrderPaid'
                    """, Integer.class, order.orderNo()));
            System.out.printf("PAYMENT_AT cancel phase1=CANCELLED outcome=absent final=%s%n", outcome.outcome());
        }
    }

    private void assertGlobalLockConflict(java.util.concurrent.Callable<?> operation) {
        var failure = assertThrows(ExecutionException.class, () -> independent(operation));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().contains("LockConflict")) return;
        }
        fail("应为 Seata 全局锁冲突", failure);
    }

    private static <T> T independent(java.util.concurrent.Callable<T> action) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            return executor.submit(action).get(10, TimeUnit.SECONDS);
        }
    }

    private String status(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM t_order WHERE order_no = ?", String.class, orderNo);
    }

    private static GlobalTransaction begin() throws Exception {
        GlobalTransaction tx = GlobalTransactionContext.getCurrentOrCreate();
        tx.begin(30000, "lesson17-payment-window");
        return tx;
    }

    private Order newOrder() {
        Order order = Order.create(17L, new Address("合成用户", "13800000000", "合成地址"));
        order.addItem(OrderItem.create(17L, 17L, "两件商品", 2, Money.of("50")));
        ownOrders.add(order.orderNo());
        return order;
    }

    private static AcceptPaymentService.PaymentFact fact(String orderNo) {
        return new AcceptPaymentService.PaymentFact(UUID.randomUUID().toString(), "PaymentSucceeded", 1,
                orderNo, UUID.randomUUID().toString(), 10000, "CNY", Instant.now());
    }
}
