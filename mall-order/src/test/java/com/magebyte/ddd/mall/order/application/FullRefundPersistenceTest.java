package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.*;
import com.magebyte.ddd.mall.order.domain.event.DomainEvent;
import com.magebyte.ddd.mall.order.infrastructure.messaging.OutboxEventDelivery;
import com.magebyte.ddd.mall.order.infrastructure.persistence.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FullRefundPersistenceTest {
    @Autowired OrderRepository orders;
    @Autowired RefundApplicationService refunds;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OutboxEventMapper outbox;
    private final List<String> own = new ArrayList<>();
    @AfterEach void quarantineOwnFacts() {
        for(String no:own) jdbc.update("UPDATE t_outbox_event SET status='FAILED' WHERE aggregate_id=? AND status='PENDING'",no);
    }
    private Order paid(boolean shipped) {
        Order o=Order.create(15L,new Address("合成用户","13800000000","合成地址")); own.add(o.orderNo());
        o.addItem(OrderItem.create(15L,15L,"退款测试商品",1,Money.of("100")));
        o.markPaid(Money.of("100"),"fixture",LocalDateTime.now());
        if(shipped) o.markShipped("fixture",LocalDateTime.now());
        return tx().execute(s->orders.save(o));
    }
    private TransactionTemplate tx() { return new TransactionTemplate(manager); }
    private FullRefundCommand command(Order o) {
        return new FullRefundCommand(o.orderNo(),"RF-"+UUID.randomUUID(),Money.of("100"),"整单退款","user:15");
    }
    private int facts(Order o,String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id=? AND event_type=?",Integer.class,o.orderNo(),name);
    }
    @Test void simulated_unknown_rejected_then_success_preserves_identity_after_each_load() {
        Order o=paid(false); var c=command(o); refunds.requestFullRefund(c);
        SimulatedRefundChannel channel=new SimulatedRefundChannel();
        for(var outcome:List.of(RefundResult.Outcome.UNKNOWN,RefundResult.Outcome.REJECTED)) {
            refunds.recordResult(o.orderNo(),channel.observe(c,outcome),"simulation");
            Order reloaded=orders.findByOrderNo(o.orderNo()).orElseThrow();
            assertEquals(OrderStatus.REFUND_REQUESTED,reloaded.status());
            assertEquals(outcome,reloaded.refund().result().outcome());
            assertTrue(reloaded.pullEvents().isEmpty());
        }
        var success=channel.observe(c,RefundResult.Outcome.SUCCEEDED);
        refunds.recordResult(o.orderNo(),success,"simulation");
        refunds.recordResult(o.orderNo(),success,"simulation"); refunds.requestFullRefund(c);
        Order loaded=orders.findByOrderNo(o.orderNo()).orElseThrow();
        assertEquals(OrderStatus.REFUNDED,loaded.status()); assertEquals(c.refundNo(),loaded.refund().refundNo());
        assertEquals(OrderStatus.PAID,loaded.refund().requestedFrom());
        assertEquals(1,facts(o,"RefundRequested")); assertEquals(1,facts(o,"RefundCompleted"));
        assertTrue(loaded.pullEvents().isEmpty());
        System.out.println("REFUND_SIMULATION unknown/rejected=REFUND_REQUESTED success=REFUNDED requestedFacts=1 completedFacts=1");
    }
    @Test void shipped_refund_keeps_original_shipment_and_emits_no_stock_command() {
        Order o=paid(true); var c=command(o); refunds.requestFullRefund(c);
        refunds.recordResult(o.orderNo(),new SimulatedRefundChannel().observe(c,RefundResult.Outcome.SUCCEEDED),"simulation");
        Order loaded=orders.findByOrderNo(o.orderNo()).orElseThrow();
        assertEquals(OrderStatus.SHIPPED,loaded.refund().requestedFrom());
        assertEquals(1,facts(o,"OrderShipped"));
        assertEquals(Set.of("OrderCreated","OrderPaid","OrderShipped","RefundRequested","RefundCompleted"),
                new HashSet<>(jdbc.queryForList("SELECT event_type FROM t_outbox_event WHERE aggregate_id=?",String.class,o.orderNo())));
        System.out.println("SHIPPED_REFUND originalShipment=1 origin=SHIPPED stockCommands=0 (order-side boundary)");
    }
    @Test void same_version_shipment_and_refund_compete_one_commit_only() throws Exception {
        Order o=paid(false); Order ship=orders.findByOrderNo(o.orderNo()).orElseThrow();
        Order refund=orders.findByOrderNo(o.orderNo()).orElseThrow(); var c=command(o);
        ship.markShipped("fixture",LocalDateTime.now());
        refund.requestFullRefund(c.refundNo(),c.amount(),c.reason(),c.operatedBy(),LocalDateTime.now());
        var ready=new CyclicBarrier(2); var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(Order candidate:List.of(ship,refund)) results.add(pool.submit(()->{
                ready.await(10,TimeUnit.SECONDS);
                try { tx().execute(s->orders.save(candidate)); return true; }
                catch(OrderDomainException conflict) { return false; }
            }));
            int commits=0; for(var f:results) if(f.get(30,TimeUnit.SECONDS)) commits++;
            assertEquals(1,commits); assertEquals(1,facts(o,"OrderShipped")+facts(o,"RefundRequested"));
            System.out.println("SHIP_REFUND_RACE commits=1 state="+orders.findByOrderNo(o.orderNo()).orElseThrow().status()+" newFacts=1");
        } finally { pool.shutdownNow(); }
    }
    @Test void rejected_mismatched_result_does_not_leave_an_outbox_fact() {
        Order o=paid(false); var c=command(o); refunds.requestFullRefund(c);
        assertThrows(OrderDomainException.class,()->refunds.recordResult(o.orderNo(),
                new RefundResult(c.refundNo(),Money.of("99"),RefundResult.Outcome.SUCCEEDED,"bad-proof"),"simulation"));
        assertEquals(OrderStatus.REFUND_REQUESTED,orders.findByOrderNo(o.orderNo()).orElseThrow().status());
        assertEquals(0,facts(o,"RefundCompleted"));
    }
    @Test void refund_identity_cannot_be_reused_on_another_order() {
        Order a=paid(false), b=paid(false); var c=command(a);
        refunds.requestFullRefund(c);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->refunds.requestFullRefund(
                new FullRefundCommand(b.orderNo(),c.refundNo(),c.amount(),c.reason(),c.operatedBy())));
        assertEquals(OrderStatus.PAID,orders.findByOrderNo(b.orderNo()).orElseThrow().status());
        assertEquals(0,facts(b,"RefundRequested"));
    }
    @Test void refund_events_can_be_read_by_real_outbox_delivery_mapping() {
        Order o=paid(false); var c=command(o); refunds.requestFullRefund(c);
        refunds.recordResult(o.orderNo(),new SimulatedRefundChannel().observe(c,RefundResult.Outcome.SUCCEEDED),"simulation");
        List<DomainEvent> sent=new ArrayList<>();
        // Only the external sender is replaced; real row locking, parsing, identity checks and SENT write remain.
        var delivery=new OutboxEventDelivery(outbox,sent::addAll,json);
        for(long id:jdbc.queryForList("SELECT id FROM t_outbox_event WHERE aggregate_id=? AND event_type IN ('RefundRequested','RefundCompleted') ORDER BY id",Long.class,o.orderNo()))
            assertEquals(Boolean.TRUE, tx().execute(s->delivery.deliver(id)));
        assertEquals(List.of("RefundRequested","RefundCompleted"),sent.stream().map(DomainEvent::eventName).toList());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id=? AND status='SENT'",Integer.class,o.orderNo()));
        System.out.println("REFUND_OUTBOX parsed=2 sentRows=2 (recording sender, not channel or broker proof)");
    }
    /** A controlled result source, never a real payment connection or an exactly-once funds guarantee. */
    private static final class SimulatedRefundChannel {
        RefundResult observe(FullRefundCommand c,RefundResult.Outcome outcome) {
            return new RefundResult(c.refundNo(),c.amount(),outcome,
                    outcome==RefundResult.Outcome.SUCCEEDED?"SIM-"+c.refundNo():"SIM-QUERY");
        }
    }
}
