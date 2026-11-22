package com.magebyte.ddd.mall.order.query;

import com.magebyte.ddd.mall.order.application.FullRefundCommand;
import com.magebyte.ddd.mall.order.application.OrderApplicationService;
import com.magebyte.ddd.mall.order.application.PartialRefundCommand;
import com.magebyte.ddd.mall.order.application.RefundApplicationService;
import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.RefundResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.magebyte.ddd.mall.order.domain.RefundResult.Outcome.*;
import static com.magebyte.ddd.mall.order.query.OrderListPage.RefundMode.*;
import static com.magebyte.ddd.mall.order.query.OrderListPage.ShipmentCheck.NOT_APPLICABLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "ddd.order.event-logger-enabled=false")
class OrderListPersistenceTest {
    @Autowired OrderRepository orders;
    @Autowired OrderListReader reader;
    @Autowired OrderListQueryService query;
    @Autowired RefundApplicationService refunds;
    @Autowired OrderApplicationService details;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate sql;
    @SpyBean JdbcClient jdbc;
    private final List<String> fixtureOrders = new ArrayList<>();

    @AfterEach void quarantineFixtureEvents() {
        for (String orderNo : fixtureOrders) {
            sql.update("UPDATE t_outbox_event SET status='FAILED' WHERE aggregate_id=? AND status='PENDING'", orderNo);
        }
    }

    @Test void summarizes_real_partial_refund_progress_across_multiple_lines() {
        Order order = create(22011, true, 1, 2);
        var lines = order.getItems();
        request(order, "REF-A", lines.get(0).lineId());
        record(order, "REF-A", UNKNOWN, "waiting");
        request(order, "REF-B", lines.get(1).lineId());
        record(order, "REF-B", SUCCEEDED, "paid-back");
        request(order, "REF-C", lines.get(1).lineId());
        record(order, "REF-C", REJECTED, "final-no-transfer");
        OrderListPage.Item row = query.list(22011, null, 20).orders().getFirst();
        assertEquals("PAID", row.fulfillmentStatus());
        assertEquals("150.00", row.paidAmount().toPlainString());
        assertEquals(List.of(lines.get(0).lineId(), lines.get(1).lineId()), row.lines().stream().map(OrderListPage.Line::lineId).toList());
        assertTrue(row.lines().stream().allMatch(line -> line.shipmentCheck() == NOT_APPLICABLE));
        assertEquals(PARTIAL, row.refund().mode());
        assertMoney("50.00", row.refund().inFlightAmount());
        assertMoney("50.00", row.refund().refundedAmount());
        assertNull(row.refund().fullStatus());
        assertEquals(3, count("SELECT COUNT(*) FROM t_order_partial_refund WHERE order_id=?", order.id()));
        record(order, "REF-A", REJECTED, "settled-without-refund");
        var refreshed = query.list(22011, null, 20).orders().getFirst().refund();
        assertMoney("0.00", refreshed.inFlightAmount());
        assertMoney("50.00", refreshed.refundedAmount());
        assertEquals(3, count("SELECT COUNT(*) FROM t_order_partial_refund WHERE order_id=?", order.id()));
        System.out.println("L22_PARTIAL_SUMMARY lines=2 applications=3 inFlight=50.00 refunded=50.00 afterFinalRejection=0.00/50.00");
    }

    @Test void keeps_legacy_full_refund_outcomes_and_original_fulfillment_separate() {
        Order order = create(22012, true, 1, 2);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            Order current = orders.findByOrderNo(order.orderNo()).orElseThrow();
            current.markShipped("fixture", LocalDateTime.now());
            orders.save(current);
        });
        var command = new FullRefundCommand(order.orderNo(), "LEGACY-FULL", Money.of("150"), "整单退款", "fixture");
        refunds.requestFullRefund(command);
        refunds.recordResult(order.orderNo(), new RefundResult("LEGACY-FULL", Money.of("150"), REJECTED,
                "legacy-observation"), "fixture");
        var pending = reader.read(22012, null, 20).orders().getFirst();
        assertEquals("SHIPPED", pending.fulfillmentStatus());
        assertEquals(FULL, pending.refund().mode());
        assertEquals("REFUND_REQUESTED", pending.refund().fullStatus());
        assertEquals("REJECTED", pending.refund().fullOutcome());
        assertMoney("150.00", pending.refund().fullRequestedAmount());
        assertNull(pending.refund().inFlightAmount());
        assertMoney("0.00", pending.refund().refundedAmount());
        refunds.recordResult(order.orderNo(), new RefundResult("LEGACY-FULL", Money.of("150"), SUCCEEDED,
                "legacy-proof"), "fixture");
        var completed = reader.read(22012, null, 20).orders().getFirst();
        assertEquals("SHIPPED", completed.fulfillmentStatus());
        assertEquals("REFUNDED", completed.refund().fullStatus());
        assertMoney("150.00", completed.refund().refundedAmount());
        System.out.println("L22_FULL_SUMMARY original=SHIPPED legacyRequested=REFUND_REQUESTED/REJECTED completed=REFUNDED/150.00");
    }

    @Test void pages_by_order_id_and_caps_line_previews() {
        Order older = create(22013, true, 1);
        Order middle = create(22013, true, 1, 1, 1, 1, 1);
        Order newer = create(22013, true, 1, 2);
        Order deleted = create(22013, true, 1);
        sql.update("UPDATE t_order SET deleted=1 WHERE id=?", deleted.id());
        create(22013, false, 1);
        create(22014, true, 1);

        // Same creation time cannot cause order-id cursor ties.
        sql.update("UPDATE t_order SET created_at='2026-01-01 12:00:00' WHERE user_id=22013");
        clearInvocations(jdbc);
        OrderListPage page = query.list(22013, null, 2);
        verify(jdbc, times(3)).sql(anyString());
        assertEquals(List.of(newer.orderNo(), middle.orderNo()), orderNos(page));
        assertEquals(middle.id(), page.nextBeforeId());
        var preview = page.orders().getLast();
        assertTrue(preview.hasMoreLines());
        assertEquals(middle.getItems().subList(0, 3).stream().map(item -> item.lineId()).toList(),
                preview.lines().stream().map(OrderListPage.Line::lineId).toList());
        assertFalse(page.orders().getFirst().hasMoreLines());

        clearInvocations(jdbc);
        OrderListPage finalPage = query.list(22013, page.nextBeforeId(), 2);
        verify(jdbc, times(3)).sql(anyString());
        assertEquals(List.of(older.orderNo()), orderNos(finalPage));
        assertNull(finalPage.nextBeforeId());

        clearInvocations(jdbc);
        assertTrue(query.list(22013, older.id(), 2).orders().isEmpty());
        verify(jdbc, times(1)).sql(anyString());
        assertEquals(5, details.getOrder(middle.orderNo()).items().size());
        System.out.println("L22_PAGINATION pageSizes=2/1/0 orderSql=3/3/1 preview=3 detail=5 removedUnpaidUnownedExcluded=true");
    }

    @Test void existing_details_materialize_refunds_per_aggregate() {
        Order a = create(22015, true, 1);
        Order b = create(22015, true, 1);
        clearInvocations(jdbc);
        for (String orderNo : List.of(a.orderNo(), b.orderNo())) {
            assertFalse(details.getOrder(orderNo).items().isEmpty());
        }
        verify(jdbc, times(2)).sql(contains("FROM t_order_partial_refund WHERE order_id = ?"));
        System.out.println("L22_DETAIL_BASELINE detailCalls=2 aggregateRefundLoads=2 separateItemAndHistoryLoads=true");
    }

    private Order create(long userId, boolean paid, int... quantities) {
        Order order = Order.create(userId, new Address("列表夹具", "13800000000", "合成地址"));
        fixtureOrders.add(order.orderNo());
        for (int i = 0; i < quantities.length; i++) {
            order.addLine(22101L, 22101L, "采购数据线", quantities[i], Money.of("50"), "PR-" + i);
        }
        if (paid) {
            order.markPaid(order.totalAmount(), "fixture", LocalDateTime.now());
        }
        return new TransactionTemplate(manager).execute(tx -> orders.save(order));
    }

    private void request(Order order, String refundNo, String lineId) {
        refunds.requestPartialRefund(new PartialRefundCommand(order.orderNo(), refundNo, lineId, 1, "退货款"));
    }

    private void record(Order order, String refundNo, RefundResult.Outcome outcome, String reference) {
        refunds.recordPartialResult(order.orderNo(), new RefundResult(refundNo, Money.of("50"), outcome, reference));
    }

    private int count(String query, long id) {
        return sql.queryForObject(query, Integer.class, id);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static List<String> orderNos(OrderListPage page) {
        return page.orders().stream().map(OrderListPage.Item::orderNo).toList();
    }
}
