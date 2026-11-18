package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.magebyte.ddd.mall.order.domain.OrderNotFoundException;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import com.magebyte.ddd.mall.order.domain.OrderPaymentJournal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderPaymentStore implements OrderPaymentJournal {
    private final JdbcClient jdbc;

    public OrderPaymentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** AT 下必须在 @GlobalLock 本地事务中调用，并在任何普通查询前取得当前行。 */
    @Override
    public Checkout lock(String orderNo) {
        return jdbc.sql("""
                SELECT order_no, payment_managed, status, total_amount
                FROM t_order WHERE order_no = ? AND deleted = 0 FOR UPDATE
                """).param(orderNo).query((rs, row) -> new Checkout(rs.getString("order_no"),
                rs.getBoolean("payment_managed"), OrderStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("total_amount"))).optional()
                .orElseThrow(() -> new OrderNotFoundException("订单不存在: " + orderNo));
    }

    @Override
    public Optional<Acceptance> acceptance(String paymentNo) {
        return jdbc.sql("""
                SELECT payment_no, order_no, outcome, fingerprint FROM t_payment_acceptance WHERE payment_no = ?
                """).param(paymentNo).query((rs, row) -> new Acceptance(rs.getString("payment_no"),
                rs.getString("order_no"), rs.getString("outcome"), rs.getString("fingerprint"))).optional();
    }

    @Override
    public Optional<Inbox> inbox(String eventId) {
        return jdbc.sql("SELECT payment_no, fingerprint FROM t_payment_inbox WHERE event_id = ?")
                .param(eventId).query((rs, row) -> new Inbox(rs.getString("payment_no"),
                        rs.getString("fingerprint"))).optional();
    }

    @Override
    public void appendAcceptance(String paymentNo, String orderNo, String fingerprint, String outcome) {
        jdbc.sql("""
                INSERT INTO t_payment_acceptance (payment_no, order_no, fingerprint, outcome)
                VALUES (?, ?, ?, ?)
                """).params(paymentNo, orderNo, fingerprint, outcome).update();
    }

    @Override
    public void appendInbox(String eventId, String paymentNo, String fingerprint) {
        jdbc.sql("""
                INSERT INTO t_payment_inbox (event_id, payment_no, fingerprint) VALUES (?, ?, ?)
                """).params(eventId, paymentNo, fingerprint).update();
    }
}
