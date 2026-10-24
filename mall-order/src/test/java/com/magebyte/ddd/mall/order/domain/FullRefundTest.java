package com.magebyte.ddd.mall.order.domain;

import com.magebyte.ddd.mall.order.domain.event.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class FullRefundTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 21, 12, 0);
    private Order pending() {
        Order o = Order.create(15L, new Address("测试用户", "13800000000", "合成地址"));
        o.addItem(OrderItem.create(15L, 15L, "教学商品", 1, Money.of("100")));
        o.pullEvents();
        return o;
    }
    private Order paid() {
        Order o = pending(); o.markPaid(Money.of("100"), "fixture", now); o.pullEvents(); return o;
    }
    private void request(Order o) { o.requestFullRefund("RF-15", Money.of("100"), "用户申请", "user:15", now); }
    private RefundResult result(RefundResult.Outcome outcome, String ref) {
        return new RefundResult("RF-15", Money.of("100"), outcome, ref);
    }
    @Test void paid_order_registers_full_refund_and_one_fact() {
        Order o=paid(); request(o);
        assertEquals(OrderStatus.REFUND_REQUESTED,o.status());
        assertEquals(OrderStatus.PAID,o.refund().requestedFrom());
        var events=o.pullEvents(); assertEquals(1,events.size());
        var e=(RefundRequestedEvent)events.getFirst();
        assertEquals("RF-15",e.refundNo()); assertEquals(Money.of("100"),e.amount());
    }
    @Test void pending_cannot_refund_and_no_fact_is_born() {
        Order o=pending(); assertThrows(OrderDomainException.class,()->request(o));
        assertEquals(OrderStatus.PENDING_PAY,o.status()); assertTrue(o.pullEvents().isEmpty());
    }
    @Test void cancelled_and_received_cannot_refund() {
        Order c=pending(); c.cancel("取消","user",now); assertThrows(OrderDomainException.class,()->request(c));
        Order o=paid(); o.markShipped("merchant",now); o.confirmReceived("user",now);
        assertThrows(OrderDomainException.class,()->request(o));
    }
    @Test void shipped_refund_preserves_shipping_fact_and_has_no_inventory_command() {
        Order o=paid(); o.markShipped("merchant",now); var shipping=o.pullEvents().getFirst();
        request(o); o.recordRefundResult(result(RefundResult.Outcome.SUCCEEDED,"proof-15"),"simulation",now);
        assertEquals(OrderStatus.SHIPPED,o.refund().requestedFrom());
        assertInstanceOf(OrderShippedEvent.class,shipping);
        assertEquals(java.util.List.of("RefundRequested","RefundCompleted"),o.pullEvents().stream().map(DomainEvent::eventName).toList());
    }
    @Test void same_application_replay_adds_no_history_or_event() {
        Order o=paid(); request(o); o.pullEvents(); request(o);
        assertEquals(3,o.statusHistory().size()); assertTrue(o.pullEvents().isEmpty());
    }
    @Test void changed_reason_amount_or_identity_cannot_create_another_refund() {
        Order o=paid(); request(o); o.pullEvents();
        assertThrows(OrderDomainException.class,()->o.requestFullRefund("RF-15",Money.of("100"),"换原因","user",now));
        assertThrows(OrderDomainException.class,()->o.requestFullRefund("RF-15",Money.of("99"),"用户申请","user",now));
        assertThrows(OrderDomainException.class,()->o.requestFullRefund("RF-16",Money.of("100"),"用户申请","user",now));
        assertTrue(o.pullEvents().isEmpty()); assertEquals("RF-15",o.refund().refundNo());
    }
    @Test void full_refund_must_equal_paid_amount() {
        Order o=paid(); assertThrows(OrderDomainException.class,()->o.requestFullRefund("RF-15",Money.of("99"),"用户申请","user",now));
        assertEquals(OrderStatus.PAID,o.status()); assertNull(o.refund());
    }
    @Test void invalid_identity_reason_and_success_without_proof_are_rejected() {
        Order o=paid(); assertThrows(OrderDomainException.class,()->o.requestFullRefund(" ",Money.of("100"),"理由","user",now));
        assertThrows(OrderDomainException.class,()->o.requestFullRefund("RF-15",Money.of("100")," ","user",now));
        assertThrows(OrderDomainException.class,()->result(RefundResult.Outcome.SUCCEEDED," "));
    }
    @Test void unknown_and_rejected_results_do_not_complete_refund() {
        Order o=paid(); request(o); o.pullEvents();
        o.recordRefundResult(result(RefundResult.Outcome.UNKNOWN,"query-1"),"simulation",now);
        assertEquals(OrderStatus.REFUND_REQUESTED,o.status());
        o.recordRefundResult(result(RefundResult.Outcome.REJECTED,"query-2"),"simulation",now);
        assertEquals(RefundResult.Outcome.REJECTED,o.refund().result().outcome());
        assertEquals(OrderStatus.REFUND_REQUESTED,o.status()); assertTrue(o.pullEvents().isEmpty());
    }
    @Test void wrong_identity_and_amount_results_are_rejected_without_mutation() {
        Order o=paid(); request(o); o.pullEvents();
        assertThrows(OrderDomainException.class,()->o.recordRefundResult(new RefundResult("OTHER",Money.of("100"),RefundResult.Outcome.SUCCEEDED,"proof"),"simulation",now));
        assertThrows(OrderDomainException.class,()->o.recordRefundResult(new RefundResult("RF-15",Money.of("99"),RefundResult.Outcome.SUCCEEDED,"proof"),"simulation",now));
        assertNull(o.refund().result()); assertTrue(o.pullEvents().isEmpty());
    }
    @Test void success_completes_once_and_replay_does_not_emit_again() {
        Order o=paid(); request(o); o.pullEvents(); var success=result(RefundResult.Outcome.SUCCEEDED,"proof-15");
        o.recordRefundResult(success,"simulation",now); var e=o.pullEvents();
        assertEquals(OrderStatus.REFUNDED,o.status()); assertEquals(1,e.size());
        assertInstanceOf(RefundCompletedEvent.class,e.getFirst());
        o.recordRefundResult(success,"simulation",now); request(o);
        assertEquals(4,o.statusHistory().size()); assertTrue(o.pullEvents().isEmpty());
    }
    @Test void completed_refund_cannot_be_downgraded_or_given_another_proof() {
        Order o=paid(); request(o); o.recordRefundResult(result(RefundResult.Outcome.SUCCEEDED,"proof-15"),"simulation",now); o.pullEvents();
        assertThrows(OrderDomainException.class,()->o.recordRefundResult(result(RefundResult.Outcome.UNKNOWN,"query"),"simulation",now));
        assertThrows(OrderDomainException.class,()->o.recordRefundResult(result(RefundResult.Outcome.SUCCEEDED,"other-proof"),"simulation",now));
        assertEquals("proof-15",o.refund().result().reference()); assertTrue(o.pullEvents().isEmpty());
    }
    @Test void requested_refund_blocks_new_shipment() {
        Order o=paid(); request(o); o.pullEvents();
        assertThrows(OrderDomainException.class,()->o.markShipped("merchant",now)); assertTrue(o.pullEvents().isEmpty());
    }
    @Test void result_before_application_is_rejected() {
        Order o=paid(); assertThrows(OrderDomainException.class,()->o.recordRefundResult(result(RefundResult.Outcome.SUCCEEDED,"proof"),"simulation",now));
        assertEquals(OrderStatus.PAID,o.status());
    }
    @Test void reconstitution_preserves_identity_but_never_republishes_history() {
        Order o=paid(); request(o); o.recordRefundResult(result(RefundResult.Outcome.SUCCEEDED,"proof-15"),"simulation",now);
        Order restored=Order.reconstitute(15L,o.orderNo(),o.userId(),o.status(),o.getItems(),o.statusHistory(),o.totalAmount(),o.paidAmount(),o.address(),1,o.createdAt(),o.updatedAt(),o.refund());
        assertEquals("RF-15",restored.refund().refundNo()); assertTrue(restored.pullEvents().isEmpty());
        restored.recordRefundResult(result(RefundResult.Outcome.SUCCEEDED,"proof-15"),"simulation",now);
        assertTrue(restored.pullEvents().isEmpty());
    }
    @Test void reconstitution_rejects_refunded_state_without_refund_identity() {
        Order o=paid(); request(o); o.recordRefundResult(result(RefundResult.Outcome.SUCCEEDED,"proof-15"),"simulation",now);
        assertThrows(OrderDomainException.class,()->Order.reconstitute(15L,o.orderNo(),o.userId(),o.status(),o.getItems(),o.statusHistory(),o.totalAmount(),o.paidAmount(),o.address(),1,o.createdAt(),o.updatedAt(),null));
    }
}
