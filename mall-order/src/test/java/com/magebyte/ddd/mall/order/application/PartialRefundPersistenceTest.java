package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.*;
import com.magebyte.ddd.mall.order.infrastructure.persistence.OrderRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.magebyte.ddd.mall.order.domain.RefundResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "ddd.order.event-logger-enabled=false")
class PartialRefundPersistenceTest {
    @SpyBean OrderRepositoryImpl orders;
    @Autowired RefundApplicationService refunds;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    private final List<String> fixtureOrders = new ArrayList<>();

    @AfterEach void quarantineFixtureEvents() {
        for (String orderNo : fixtureOrders) {
            jdbc.update("UPDATE t_outbox_event SET status='FAILED' WHERE aggregate_id=? AND status='PENDING'", orderNo);
        }
    }

    @Test void two_full_line_requests_have_only_one_winner() throws Exception {
        Order order = order(true, true);
        var race = race(order, () -> submit(order, "A", 2), () -> submit(order, "B", 2));
        assertEquals(1, race.applied());
        assertEquals(List.of("部分退款超过订单行数量"), race.rejections());
        assertEquals(3, race.versions().size());
        assertEquals(order.version() + 1, race.versions().getLast());
        assertUsage(order, 1, 2, "100");
        System.out.printf("L21_OVER_LIMIT versions=%s connections=%s rows=1 quantity=2 amount=100.00 reloadedRejection=%s%n",
                race.versions(), race.connections(), race.rejections().getFirst());
    }

    @Test void two_legal_requests_both_commit_after_a_version_conflict() throws Exception {
        Order order = order(true, true);
        var race = race(order, () -> submit(order, "A", 1), () -> submit(order, "B", 1));
        assertEquals(2, race.applied());
        assertTrue(race.rejections().isEmpty());
        assertEquals(List.of(order.version(), order.version(), order.version() + 1), race.versions());
        assertEquals(order.version() + 2, reload(order).version());
        assertUsage(order, 2, 2, "100");
        System.out.printf("L21_LEGAL_BOTH versions=%s connections=%s rows=2 quantity=2 amount=100.00%n",
                race.versions(), race.connections());
    }

    @Test void same_number_and_parameters_are_idempotent_even_under_concurrency() throws Exception {
        Order order = order(true, true);
        var race = race(order, () -> submit(order, "SAME", 1), () -> submit(order, "SAME", 1));
        assertEquals(2, race.applied());
        assertEquals(List.of(order.version(), order.version(), order.version() + 1), race.versions());
        assertEquals(order.version() + 1, reload(order).version());
        assertEquals(row(order, "SAME"), submit(order, "SAME", 1));
        assertThrows(OrderDomainException.class, () -> submit(order, "SAME", 2));
        assertThrows(OrderDomainException.class, () -> refunds.requestPartialRefund(
                new PartialRefundCommand(order.orderNo(), "SAME", line(order, "PR-A"), 1, "退货款")));
        assertThrows(OrderDomainException.class, () -> refunds.requestPartialRefund(
                new PartialRefundCommand(order.orderNo(), "SAME", line(order, "PR-B"), 1, "改原因")));
        assertUsage(order, 1, 1, "50");
        System.out.printf("L21_SAME_NUMBER versions=%s committedVersions=1 successfulCalls=2 rows=1 quantity=1 amount=50.00%n",
                race.versions());
    }

    @Test void unknown_holds_capacity_and_final_rejection_releases_it() {
        Order order = order(true, true);
        var registered = submit(order, "A", 2);
        assertEquals(OrderStatus.SHIPPED, registered.requestedFrom());
        assertEquals(line(order, "PR-B"), registered.lineId());
        assertNull(registered.result());
        refunds.recordPartialResult(order.orderNo(), result("A", "100", UNKNOWN, "query-pending"));
        assertEquals(UNKNOWN, row(order, "A").result().outcome());
        assertUsage(order, 1, 2, "100");
        assertThrows(OrderDomainException.class, () -> submit(order, "B", 1));
        assertThrows(OrderDomainException.class, () -> refunds.recordPartialResult(order.orderNo(),
                result("A", "100", REJECTED, "")));
        refunds.recordPartialResult(order.orderNo(), result("A", "100", REJECTED, "final-no-transfer"));
        assertEquals(REJECTED, row(order, "A").result().outcome());
        assertUsage(order, 1, 0, "0");
        assertSame(RefundResult.Outcome.REJECTED, submit(order, "A", 2).result().outcome());
        submit(order, "B", 2);
        assertUsage(order, 2, 2, "100");
        assertThrows(OrderDomainException.class, () -> refunds.recordPartialResult(order.orderNo(),
                result("A", "100", SUCCEEDED, "inconsistent-success")));
        assertThrows(OrderDomainException.class, () -> refunds.requestFullRefund(full(order)));
        assertEquals("final-no-transfer", row(order, "A").result().reference());
        assertEquals(OrderStatus.SHIPPED, reload(order).status());
        assertEquals(0, refundEvents(order));
        System.out.println("L21_UNKNOWN_REJECTED occupied=2/100.00 afterFinalRejection=0/0.00 afterNewRequest=2/100.00 oldRow=REJECTED");
    }

    @Test void success_matches_original_amount_and_preserves_fulfillment() {
        Order order = order(true, false);
        submit(order, "A", 1);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            Order candidate = reload(order);
            candidate.markShipped("fixture", LocalDateTime.now());
            candidate.confirmReceived("fixture", LocalDateTime.now());
            orders.save(candidate);
        });
        assertEquals(OrderStatus.PAID, row(order, "A").requestedFrom());
        assertThrows(OrderDomainException.class, () -> submit(order, "NEW", 1));
        assertThrows(OrderDomainException.class, () -> refunds.recordPartialResult(order.orderNo(),
                result("A", "60", SUCCEEDED, "new-catalog-price")));
        assertThrows(OrderDomainException.class, () -> refunds.recordPartialResult(order.orderNo(),
                result("OTHER", "50", SUCCEEDED, "wrong-identity")));
        RefundResult success = result("A", "50", SUCCEEDED, "successful-original");
        refunds.recordPartialResult(order.orderNo(), success);
        Integer succeededVersion = reload(order).version();
        assertEquals(success, refunds.recordPartialResult(order.orderNo(), success).result());
        assertEquals(succeededVersion, reload(order).version());
        assertEquals(OrderStatus.RECEIVED, reload(order).status());
        assertEquals(success, row(order, "A").result());
        assertUsage(order, 1, 1, "50");
        assertEquals(0, refundEvents(order));
        System.out.println("L21_SUCCESS fulfillment=RECEIVED source=PAID ledger=SUCCEEDED quantity=1 amount=50.00 successReplayWrites=0");
    }

    @Test void line_limits_and_eligibility_are_independent_of_order_total() {
        Order order = order(true, true);
        Order another = order(true, true);
        Order unpaid = order(false, false);
        var first = new PartialRefundCommand(order.orderNo(), "A", line(order, "PR-A"), 1, "退货款");
        assertEquals(Money.of("50"), refunds.requestPartialRefund(first).amount());
        assertThrows(OrderDomainException.class, () -> refunds.requestPartialRefund(
                new PartialRefundCommand(order.orderNo(), "B", first.lineId(), 1, first.reason())));
        assertThrows(OrderDomainException.class, () -> refunds.requestPartialRefund(
                new PartialRefundCommand(order.orderNo(), "B", line(another, "PR-B"), 1, first.reason())));
        assertThrows(OrderDomainException.class, () -> submit(unpaid, "A", 1));
        assertThrows(OrderDomainException.class, () -> submit(order, "ZERO", 0));
        assertThrows(OrderDomainException.class, () -> submit(order, "NEGATIVE", -1));
        assertEquals(Money.of("100"), submit(order, "B", 2).amount());
        assertUsage(order, 2, 3, "150");
        System.out.println("L21_LINE_AND_ORDER_LIMIT lineA=1/50.00 lineB=2/100.00 purchased=3 paid=150.00");
    }

    @Test void full_and_partial_refunds_share_the_same_root_version() throws Exception {
        Order order = order(true, false);
        var race = race(order, () -> refunds.requestFullRefund(full(order)), () -> submit(order, "PART", 1));
        assertEquals(1, race.applied());
        assertEquals(1, race.rejections().size());
        Order stored = reload(order);
        if (stored.refund() == null) {
            assertEquals(1, stored.partialRefunds().size());
            assertEquals(0, refundEvents(order));
            assertThrows(OrderDomainException.class, () -> refunds.requestFullRefund(full(order)));
        } else {
            assertEquals(1, refundEvents(order));
            assertTrue(stored.partialRefunds().isEmpty());
            assertThrows(OrderDomainException.class, () -> submit(order, "PART", 1));
        }
        assertEquals(order.version() + 1, stored.version());
        System.out.printf("L21_MODES versions=%s full=%s partialRows=%d%n",
                race.versions(), stored.refund() != null, stored.partialRefunds().size());
    }

    @Test void concurrent_final_rejection_and_new_request_preserve_capacity() throws Exception {
        Order order = order(true, true);
        submit(order, "A", 1);
        Order requested = reload(order);
        var race = race(requested, () -> refunds.recordPartialResult(order.orderNo(),
                result("A", "50", REJECTED, "final-rejection")), () -> submit(order, "B", 1));
        assertEquals(2, race.applied());
        assertEquals(List.of(requested.version(), requested.version(), requested.version() + 1), race.versions());
        assertEquals(REJECTED, row(order, "A").result().outcome());
        assertUsage(order, 2, 1, "50");
    }

    @Test void root_and_refund_rows_roll_back_together() {
        Order order = order(true, false);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            Order candidate = reload(order);
            candidate.requestPartialRefund("A", line(order, "PR-B"), 2, "退货款", LocalDateTime.now());
            orders.save(candidate);
            assertUsage(order, 1, 2, "100");
            throw new IllegalStateException("教学故障：写入后、提交前失败");
        }));
        assertEquals(order.version(), reload(order).version());
        assertUsage(order, 0, 0, "0");
        assertEquals(Money.of("100"), submit(order, "A", 2).amount());
        assertUsage(order, 1, 2, "100");
        System.out.println("L21_ROLLBACK versionUnchanged=true leakedRows=0 sameRequestRetry=ACCEPTED");
    }

    private Race race(Order order, Callable<?> left, Callable<?> right) throws Exception {
        var startingRead = new CyclicBarrier(2);
        var inWorker = new ThreadLocal<Boolean>();
        var firstRead = new ThreadLocal<Boolean>();
        var versions = new ConcurrentLinkedQueue<Integer>();
        var connections = new ConcurrentLinkedQueue<Long>();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<Order> loaded = (Optional<Order>) invocation.callRealMethod();
            if (Boolean.TRUE.equals(inWorker.get())) {
                versions.add(loaded.orElseThrow().version());
                if (Boolean.TRUE.equals(firstRead.get())) {
                    firstRead.remove();
                    connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    startingRead.await(15, TimeUnit.SECONDS);
                }
            }
            return loaded;
        }).when(orders).findByOrderNo(order.orderNo());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = List.of(left, right).stream().map(action -> pool.submit(() -> {
                inWorker.set(true);
                firstRead.set(true);
                try {
                    action.call();
                    return Optional.<String>empty();
                } catch (OrderDomainException e) {
                    return Optional.of(e.getMessage());
                } finally {
                    inWorker.remove();
                    firstRead.remove();
                }
            })).toList();
            var a = results.getFirst().get(30, TimeUnit.SECONDS);
            var b = results.getLast().get(30, TimeUnit.SECONDS);
            var rejections = java.util.stream.Stream.of(a, b).flatMap(Optional::stream).toList();
            var readVersions = List.copyOf(versions);
            assertEquals(List.of(order.version(), order.version()), readVersions.subList(0, 2));
            assertEquals(2, connections.stream().distinct().count());
            return new Race(readVersions, List.copyOf(connections), 2 - rejections.size(), rejections);
        }
    }

    private record Race(List<Integer> versions, List<Long> connections, int applied, List<String> rejections) {}

    private Order order(boolean paid, boolean shipped) {
        Order order = Order.create(21L, new Address("教学用户", "13800000000", "教学地址"));
        fixtureOrders.add(order.orderNo());
        order.addLine(20101L, 20101L, "数据线", 1, Money.of("50"), "PR-A");
        order.addLine(20101L, 20101L, "数据线", 2, Money.of("50"), "PR-B");
        if (paid) {
            order.markPaid(Money.of("150"), "fixture", LocalDateTime.now());
        }
        if (shipped) {
            order.markShipped("fixture", LocalDateTime.now());
        }
        return new TransactionTemplate(manager).execute(tx -> orders.save(order));
    }

    private Order reload(Order order) {
        return orders.findByOrderNo(order.orderNo()).orElseThrow();
    }

    private PartialRefund submit(Order order, String refundNo, int quantity) {
        return refunds.requestPartialRefund(new PartialRefundCommand(order.orderNo(), refundNo,
                line(order, "PR-B"), quantity, "退货款"));
    }

    private static String line(Order order, String request) {
        return order.getItems().stream().filter(item -> request.equals(item.purchaseRequestNo()))
                .findFirst().orElseThrow().lineId();
    }

    private static RefundResult result(String no, String amount, RefundResult.Outcome outcome, String proof) {
        return new RefundResult(no, Money.of(amount), outcome, proof);
    }

    private static FullRefundCommand full(Order order) {
        return new FullRefundCommand(order.orderNo(), "FULL-" + order.orderNo(), Money.of("150"), "整单退款", "fixture");
    }

    private PartialRefund row(Order order, String refundNo) {
        return reload(order).partialRefund(refundNo);
    }

    private int refundEvents(Order order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id=? AND event_type LIKE 'Refund%'",
                Integer.class, order.orderNo());
    }

    private void assertUsage(Order order, int rows, int quantity, String amount) {
        jdbc.queryForObject("""
                SELECT COUNT(*) AS rows_written,
                       COALESCE(SUM(CASE WHEN outcome='REJECTED' THEN 0 ELSE quantity END), 0) AS used_quantity,
                       COALESCE(SUM(CASE WHEN outcome='REJECTED' THEN 0 ELSE amount END), 0) AS used_amount
                FROM t_order_partial_refund WHERE order_id=?
                """, (rs, index) -> {
                    assertEquals(rows, rs.getInt("rows_written"));
                    assertEquals(quantity, rs.getInt("used_quantity"));
                    assertEquals(Money.of(amount), Money.of(rs.getBigDecimal("used_amount")));
                    return true;
                }, order.id());
    }
}
