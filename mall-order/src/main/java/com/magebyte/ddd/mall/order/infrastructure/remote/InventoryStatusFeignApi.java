package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.order.query.OrderListInventory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.Instant;
import java.util.List;

@FeignClient(name = "mall-inventory", contextId = "inventoryStatusClient",
        url = "${ddd.order.inventory-status-base-url:}")
public interface InventoryStatusFeignApi {
    @PostMapping("/internal/inventory-reservations/batch-query")
    Result<Batch> read(@RequestBody Request request);

    record Request(List<String> reservationNos) {}
    record Batch(Instant observedAt, List<OrderListInventory.Reservation> reservations) {}
}
