package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.magebyte.ddd.mall.order.query.OrderListPage;
import com.magebyte.ddd.mall.order.query.OrderListReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.magebyte.ddd.mall.order.query.OrderListPage.RefundMode.*;
import static com.magebyte.ddd.mall.order.query.OrderListPage.ShipmentCheck.NOT_APPLICABLE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

@Repository
public class JdbcOrderListReader implements OrderListReader {
    private static final int PREVIEW_LINES = 3;
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final JdbcClient jdbc;

    public JdbcOrderListReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderListPage read(long userId, Long beforeId, int limit) {
        List<Header> headers = jdbc.sql("""
                SELECT id, order_no, created_at, status, paid_amount, refund_no, refund_amount,
                       refund_from_status, refund_outcome
                FROM t_order
                WHERE user_id = :userId AND deleted = 0 AND paid_amount IS NOT NULL
                """ + (beforeId == null ? "" : "AND id < :beforeId\n") + """
                ORDER BY id DESC
                LIMIT :fetch
                """).param("userId", userId).param("beforeId", beforeId).param("fetch", limit + 1)
                .query(Header.class).list();
        if (headers.isEmpty()) {
            return new OrderListPage(List.of(), null, null);
        }
        var page = headers.subList(0, Math.min(headers.size(), limit));
        var ids = page.stream().map(Header::id).toList();
        var lines = readLines(ids).stream().collect(groupingBy(LineRow::orderId));
        var refunds = readPartialRefunds(ids).stream().collect(toMap(PartialTotals::orderId, row -> row));

        var orders = page.stream().map(header -> {
            List<LineRow> previews = lines.getOrDefault(header.id(), List.of());
            var visible = previews.stream().limit(PREVIEW_LINES).map(LineRow::toView).toList();
            String fulfillment = header.refundNo() != null ? header.refundFromStatus() : header.status();
            return new OrderListPage.Item(header.orderNo(), header.createdAt(), fulfillment, header.paidAmount(),
                    refundSummary(header, refunds.get(header.id())), visible, previews.size() > PREVIEW_LINES);
        }).toList();
        return new OrderListPage(orders, headers.size() > limit ? ids.getLast() : null, null);
    }

    private List<LineRow> readLines(List<Long> ids) {
        return jdbc.sql("""
                SELECT order_id, line_id, sku_id, product_name, quantity, unit_price, reservation_no
                FROM (
                    SELECT order_id, id, line_id, sku_id, product_name, quantity, unit_price, reservation_no,
                           ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY id) AS line_rank
                    FROM t_order_item
                    WHERE order_id IN (:ids)
                ) ranked
                WHERE line_rank <= :fetch
                ORDER BY order_id, id
                """).param("ids", ids).param("fetch", PREVIEW_LINES + 1).query(LineRow.class).list();
    }

    private List<PartialTotals> readPartialRefunds(List<Long> ids) {
        return jdbc.sql("""
                SELECT order_id,
                       SUM(CASE WHEN outcome IS NULL OR outcome = 'UNKNOWN' THEN amount ELSE 0 END) AS in_flight,
                       SUM(CASE WHEN outcome = 'SUCCEEDED' THEN amount ELSE 0 END) AS refunded
                FROM t_order_partial_refund
                WHERE order_id IN (:ids)
                GROUP BY order_id
                """).param("ids", ids).query(PartialTotals.class).list();
    }

    private static OrderListPage.RefundSummary refundSummary(Header header, PartialTotals totals) {
        if (header.refundNo() != null) {
            return new OrderListPage.RefundSummary(FULL, header.status(), header.refundOutcome(),
                    header.refundAmount(), null, "REFUNDED".equals(header.status()) ? header.refundAmount() : ZERO);
        }
        if (totals != null) {
            return new OrderListPage.RefundSummary(PARTIAL, null, null, null, totals.inFlight(), totals.refunded());
        }
        return new OrderListPage.RefundSummary(NONE, null, null, null, null, ZERO);
    }

    private record Header(long id, String orderNo, LocalDateTime createdAt, String status, BigDecimal paidAmount,
                          String refundNo, BigDecimal refundAmount, String refundFromStatus, String refundOutcome) {}

    private record LineRow(long orderId, String lineId, long skuId, String productName, int quantity,
                           BigDecimal unitPrice, String reservationNo) {
        OrderListPage.Line toView() {
            return new OrderListPage.Line(lineId, skuId, productName, quantity, unitPrice, reservationNo, NOT_APPLICABLE);
        }
    }

    private record PartialTotals(long orderId, BigDecimal inFlight, BigDecimal refunded) {}
}
