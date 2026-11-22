package com.magebyte.ddd.mall.order.query;

import org.springframework.stereotype.Service;

import java.util.Optional;

import static com.magebyte.ddd.mall.order.query.OrderListPage.ShipmentCheck.*;

@Service
public class OrderListQueryService {
    public static final int MAX_PAGE_SIZE = 20;
    private final OrderListReader reader;
    private final OrderListInventory inventory;

    public OrderListQueryService(OrderListReader reader, OrderListInventory inventory) {
        this.reader = reader;
        this.inventory = inventory;
    }

    public OrderListPage list(long userId, Long beforeId, int limit) {
        var page = reader.read(userId, beforeId, limit);
        var reservationNos = page.orders().stream()
                .filter(OrderListPage.Item::shipmentRecorded)
                .flatMap(order -> order.lines().stream())
                .map(OrderListPage.Line::reservationNo)
                .filter(no -> no != null)
                .distinct().toList();

        // 先结束订单库的只读事务，再取库存侧的一次独立观察。
        var observation = reservationNos.isEmpty() ? Optional.<OrderListInventory.Observation>empty()
                : inventory.observe(reservationNos);
        var orders = page.orders().stream()
                .map(order -> order.shipmentRecorded()
                        ? order.withLines(order.lines().stream().map(line -> observe(line, observation)).toList())
                        : order)
                .toList();
        return new OrderListPage(orders, page.nextBeforeId(),
                observation.map(OrderListInventory.Observation::observedAt).orElse(null));
    }

    private OrderListPage.Line observe(OrderListPage.Line line, Optional<OrderListInventory.Observation> observation) {
        if (line.reservationNo() == null) {
            return line.withShipmentCheck(NOT_VERIFIED);
        }
        if (observation.isEmpty()) {
            return line.withShipmentCheck(UNAVAILABLE);
        }
        var reservation = observation.get().reservations().get(line.reservationNo());
        if (reservation == null) {
            return line.withShipmentCheck(NOT_FOUND);
        }
        if (!("SKU-" + line.skuId()).equals(reservation.skuCode()) || line.quantity() != reservation.quantity()) {
            return line.withShipmentCheck(MISMATCH);
        }
        return line.withShipmentCheck(switch (reservation.state()) {
            case RESERVED -> RESERVED;
            case CONFIRMED -> CONFIRMED;
            case RELEASED -> RELEASED;
        });
    }
}
