package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import com.magebyte.ddd.mall.order.domain.OrderPaymentJournal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.apache.seata.spring.annotation.GlobalLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcceptPaymentService {
    private final OrderPaymentJournal store;
    private final OrderRepository orders;

    public AcceptPaymentService(OrderPaymentJournal store, OrderRepository orders) {
        this.store = store;
        this.orders = orders;
    }

    public record PaymentFact(String eventId, String eventName, int schemaVersion, String orderNo,
                              String paymentNo, long amountMinor, String currency, Instant confirmedAt) {
        public PaymentFact {
            if (!validIdentity(eventId, 48) || !validIdentity(orderNo, 64) || !validIdentity(paymentNo, 48)
                    || !"PaymentSucceeded".equals(eventName) || schemaVersion != 1 || amountMinor <= 0
                    || !"CNY".equals(currency) || confirmedAt == null) {
                throw new IllegalArgumentException("支付成功事实的契约不成立");
            }
        }

        private static boolean validIdentity(String value, int max) {
            return value != null && value.length() <= max && value.matches("[A-Za-z0-9_-]+");
        }

        public String fingerprint() {
            // IDs cannot contain a line break. Event ID is checked independently from the business fact.
            String content = String.join("\n", orderNo, paymentNo, Long.toString(amountMinor),
                    currency, confirmedAt.toString());
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public record Checkout(String orderNo, long amountMinor, String currency) {}
    public record Outcome(String paymentNo, String orderNo, String outcome) {}

    @GlobalLock(lockRetryInterval = 100, lockRetryTimes = 3)
    @Transactional
    public Checkout checkout(String orderNo) {
        var order = store.lock(orderNo);
        if (!order.managed() || order.status() != OrderStatus.PENDING_PAY || order.total().signum() <= 0) {
            throw new OrderDomainException("该订单当前不可创建支付单");
        }
        return new Checkout(order.orderNo(), order.total().movePointRight(2).longValueExact(), "CNY");
    }

    @GlobalLock(lockRetryInterval = 100, lockRetryTimes = 3)
    @Transactional
    public void accept(PaymentFact fact) {
        // A local lock alone can still see the provisional outcome of an AT branch.
        var current = store.lock(fact.orderNo());
        String fingerprint = fact.fingerprint();
        var previousEvent = store.inbox(fact.eventId());
        if (previousEvent.isPresent()) {
            var receipt = previousEvent.get();
            if (!receipt.paymentNo().equals(fact.paymentNo()) || !receipt.fingerprint().equals(fingerprint)) {
                throw new OrderDomainException("交接事件 ID 与事实冲突: " + fact.eventId());
            }
            return;
        }
        var previousPayment = store.acceptance(fact.paymentNo());
        if (previousPayment.isPresent()) {
            if (!previousPayment.get().fingerprint().equals(fingerprint)) {
                throw new OrderDomainException("支付单与已处理事实冲突: " + fact.paymentNo());
            }
        } else {
            String outcome = decide(fact, current);
            store.appendAcceptance(fact.paymentNo(), fact.orderNo(), fingerprint, outcome);
        }
        store.appendInbox(fact.eventId(), fact.paymentNo(), fingerprint);
    }

    public Optional<Outcome> findOutcome(String paymentNo) {
        return store.acceptance(paymentNo).map(row -> new Outcome(row.paymentNo(), row.orderNo(), row.outcome()));
    }

    private String decide(PaymentFact fact, OrderPaymentJournal.Checkout current) {
        if (!current.managed()) return "REJECTED_LEGACY_ORDER";
        if (current.status() == OrderStatus.CANCELLED) return "REVIEW_CANCELLED_ORDER";
        if (current.status() != OrderStatus.PENDING_PAY) return "REVIEW_ORDER_STATE";
        BigDecimal amount = BigDecimal.valueOf(fact.amountMinor(), 2);
        if (current.total().compareTo(amount) != 0) return "REVIEW_AMOUNT";
        Order order = orders.findByOrderNo(fact.orderNo()).orElseThrow();
        order.markPaid(Money.of(amount), "payment-handoff", LocalDateTime.now());
        orders.save(order);
        return "ACCEPTED";
    }
}
