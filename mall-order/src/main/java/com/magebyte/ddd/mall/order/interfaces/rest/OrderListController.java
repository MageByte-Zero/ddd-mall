package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.order.query.OrderListPage;
import com.magebyte.ddd.mall.order.query.OrderListQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderListController {
    private final OrderListQueryService query;

    public OrderListController(OrderListQueryService query) {
        this.query = query;
    }

    @GetMapping("/internal/order-list")
    public Result<OrderListPage> list(@RequestParam @Positive long userId,
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(OrderListQueryService.MAX_PAGE_SIZE) int limit) {
        return Result.ok(query.list(userId, beforeId, limit));
    }
}
