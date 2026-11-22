package com.magebyte.ddd.mall.inventory.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class ReservationStatusQuery {
    public static final int MAX_BATCH_SIZE = 60;
    private final JdbcClient jdbc;

    public ReservationStatusQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Batch read(List<String> reservationNos) {
        List<ReservationStatus> rows = jdbc.sql("""
                SELECT reservation_no, sku_code, quantity, state
                FROM t_inventory_reservation WHERE reservation_no IN (:reservationNos)
                """).param("reservationNos", reservationNos).query(ReservationStatus.class).list();
        return new Batch(Instant.now(), rows);
    }

    public record Batch(Instant observedAt, List<ReservationStatus> reservations) {}
    public record ReservationStatus(String reservationNo, String skuCode, int quantity, String state) {}
}
