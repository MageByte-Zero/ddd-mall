package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static com.magebyte.ddd.mall.order.domain.RefundResult.Outcome.SUCCEEDED;
import static com.magebyte.ddd.mall.order.domain.RefundResult.Outcome.UNKNOWN;
import static org.junit.jupiter.api.Assertions.*;

/** 先在 lesson-20 执行 seedV4，然后在同一隔离库执行 readV5。 */
@SpringBootTest(properties = "ddd.order.event-logger-enabled=false")
class PartialRefundUpgradeVerification {
    private static final String PENDING = "L21-V4-PENDING";
    private static final String COMPLETED = "L21-V4-COMPLETED";
    @Autowired OrderRepository orders;
    @Autowired RefundApplicationService refunds;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;

    @Test void seedV4() {
        assertEquals("4", schemaVersion());
        Order pending = paid(false);
        Order completed = paid(true);
        var error = assertThrows(OrderDomainException.class, () -> refunds.requestFullRefund(
                new FullRefundCommand(completed.orderNo(), "L21-BASELINE", Money.of("50"), "退一根", "fixture")));
        assertEquals(OrderStatus.SHIPPED, reload(completed.orderNo()).status());
        assertNull(reload(completed.orderNo()).refund());
        assertTrue(refundFacts(completed.orderNo()).isEmpty());
        System.out.printf("L21_BASELINE v4 requested=50.00 paid=150.00 status=SHIPPED refundFacts=0 error=%s%n",
                error.getMessage());
        refunds.requestFullRefund(new FullRefundCommand(pending.orderNo(), PENDING, Money.of("150"), "整单退款", "fixture"));
        refunds.recordResult(pending.orderNo(), new RefundResult(PENDING, Money.of("150"), UNKNOWN, "old-query"), "fixture");
        refunds.requestFullRefund(new FullRefundCommand(completed.orderNo(), COMPLETED, Money.of("150"), "整单退款", "fixture"));
        refunds.recordResult(completed.orderNo(), new RefundResult(COMPLETED, Money.of("150"), SUCCEEDED, "old-success"), "fixture");
        System.out.println("L21_V4 seeded=REFUND_REQUESTED/UNKNOWN,REFUNDED/SUCCEEDED");
    }

    @Test void readV5() {
        assertEquals("5", schemaVersion());
        Order pending = readByRefundNo(PENDING);
        Order completed = readByRefundNo(COMPLETED);
        assertEquals(OrderStatus.REFUND_REQUESTED, pending.status());
        assertEquals(OrderStatus.PAID, pending.refund().requestedFrom());
        assertEquals(new RefundResult(PENDING, Money.of("150"), UNKNOWN, "old-query"), pending.refund().result());
        assertEquals(List.of("RefundRequested"), refundFacts(pending.orderNo()));
        assertEquals(OrderStatus.REFUNDED, completed.status());
        assertEquals(OrderStatus.SHIPPED, completed.refund().requestedFrom());
        assertEquals(new RefundResult(COMPLETED, Money.of("150"), SUCCEEDED, "old-success"), completed.refund().result());
        assertEquals(List.of("RefundRequested", "RefundCompleted"), refundFacts(completed.orderNo()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_order_partial_refund", Integer.class));

        var success = new RefundResult(PENDING, Money.of("150"), SUCCEEDED, "after-upgrade");
        refunds.recordResult(pending.orderNo(), success, "fixture");
        refunds.recordResult(pending.orderNo(), success, "fixture");
        assertEquals(success, reload(pending.orderNo()).refund().result());
        assertEquals(OrderStatus.REFUNDED, reload(pending.orderNo()).status());
        assertEquals(List.of("RefundRequested", "RefundCompleted"), refundFacts(pending.orderNo()));
        System.out.println("L21_V5 migrated=2 preservedOldStates=true partialRows=0 oldPendingFinished=true replayFacts=2");
    }

    private Order paid(boolean shipped) {
        Order order = Order.create(21L, new Address("教学用户", "13800000000", "教学地址"));
        order.addLine(20101L, 20101L, "数据线", 1, Money.of("50"), "PR-A");
        order.addLine(20101L, 20101L, "数据线", 2, Money.of("50"), "PR-B");
        order.markPaid(Money.of("150"), "fixture", LocalDateTime.now());
        if (shipped) {
            order.markShipped("fixture", LocalDateTime.now());
        }
        return new TransactionTemplate(manager).execute(tx -> orders.save(order));
    }

    private Order readByRefundNo(String refundNo) {
        String orderNo = jdbc.queryForObject("SELECT order_no FROM t_order WHERE refund_no=?", String.class, refundNo);
        return reload(orderNo);
    }

    private Order reload(String orderNo) {
        return orders.findByOrderNo(orderNo).orElseThrow();
    }

    private List<String> refundFacts(String orderNo) {
        return jdbc.queryForList("SELECT event_type FROM t_outbox_event WHERE aggregate_id=? AND event_type LIKE 'Refund%' ORDER BY id",
                String.class, orderNo);
    }

    private String schemaVersion() {
        return jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class);
    }
}
