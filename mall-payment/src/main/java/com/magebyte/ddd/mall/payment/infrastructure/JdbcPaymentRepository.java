package com.magebyte.ddd.mall.payment.infrastructure;

import com.magebyte.ddd.mall.payment.domain.Payment;
import com.magebyte.ddd.mall.payment.domain.PaymentAmount;
import com.magebyte.ddd.mall.payment.domain.PaymentRepository;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentRepository implements PaymentRepository {
    private final JdbcClient jdbc;

    public JdbcPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Payment> find(String paymentNo) {
        return read("SELECT * FROM t_payment WHERE payment_no = ?", paymentNo);
    }

    @Override
    public Optional<Payment> findByOrder(String orderNo) {
        return read("SELECT * FROM t_payment WHERE order_no = ?", orderNo);
    }

    @Override
    public Optional<Payment> lock(String paymentNo) {
        return read("SELECT * FROM t_payment WHERE payment_no = ? FOR UPDATE", paymentNo);
    }

    @Override
    public void insert(Payment payment) {
        jdbc.sql("""
                INSERT INTO t_payment (payment_no, order_no, amount_minor, currency, status, channel)
                VALUES (?, ?, ?, ?, 'PENDING', ?)
                """).params(payment.paymentNo(), payment.orderNo(), payment.amount().minorUnits(),
                payment.amount().currency(), payment.channel()).update();
    }

    @Override
    public void confirm(Payment payment) {
        int changed = jdbc.sql("""
                UPDATE t_payment SET status = 'SUCCEEDED', channel_txn_no = ?, confirmed_at = ?
                WHERE payment_no = ? AND status = 'PENDING'
                """).params(payment.channelTxnNo(), Timestamp.from(payment.confirmedAt()), payment.paymentNo())
                .update();
        if (changed != 1) throw new IllegalStateException("支付单确认未保存");
    }

    private Optional<Payment> read(String sql, String id) {
        return jdbc.sql(sql).param(id).query((rs, row) -> {
            Timestamp confirmed = rs.getTimestamp("confirmed_at");
            return new Payment(rs.getString("payment_no"), rs.getString("order_no"),
                    new PaymentAmount(rs.getLong("amount_minor"), rs.getString("currency")),
                    rs.getString("channel"), rs.getString("channel_txn_no"),
                    confirmed == null ? null : confirmed.toInstant());
        }).optional();
    }
}
