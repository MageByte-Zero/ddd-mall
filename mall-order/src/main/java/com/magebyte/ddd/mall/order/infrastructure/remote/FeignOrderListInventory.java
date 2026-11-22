package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.order.query.OrderListInventory;
import feign.FeignException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class FeignOrderListInventory implements OrderListInventory {
    private final InventoryStatusFeignApi client;

    public FeignOrderListInventory(InventoryStatusFeignApi client) {
        this.client = client;
    }

    @Override
    public Optional<Observation> observe(List<String> reservationNos) {
        Result<InventoryStatusFeignApi.Batch> response;
        try {
            response = client.read(new InventoryStatusFeignApi.Request(reservationNos));
        } catch (FeignException unavailable) {
            return Optional.empty();
        }
        if (response == null || response.getCode() != Result.CODE_SUCCESS || response.getData() == null) {
            return Optional.empty();
        }
        var batch = response.getData();
        if (batch.observedAt() == null || batch.reservations() == null) {
            return Optional.empty();
        }
        Set<String> requested = Set.copyOf(reservationNos);
        Map<String, Reservation> received = new HashMap<>();
        for (var item : batch.reservations()) {
            if (item == null || item.reservationNo() == null || !requested.contains(item.reservationNo())
                    || item.state() == null || received.putIfAbsent(item.reservationNo(), item) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(new Observation(batch.observedAt(), Map.copyOf(received)));
    }
}
