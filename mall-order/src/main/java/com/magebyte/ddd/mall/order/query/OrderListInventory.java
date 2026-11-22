package com.magebyte.ddd.mall.order.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OrderListInventory {
    Optional<Observation> observe(List<String> reservationNos);

    record Observation(Instant observedAt, Map<String, Reservation> reservations) {}
    record Reservation(String reservationNo, String skuCode, int quantity, State state) {}
    enum State { RESERVED, CONFIRMED, RELEASED }
}
