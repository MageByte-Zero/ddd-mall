package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.order.application.OrderApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口层（第 10 讲最小形态：只开创建订单入口，
 * 支付/取消等用例在订单用例串联讲次补齐）。
 *
 * <p>创建订单 = 全局事务入口：请求进到这里，应用服务开启全局事务，
 * 订单库与库存库要么都成功、要么都回滚。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    /**
     * 创建订单。成功返回 201 + 订单号；库存不足等业务失败返回 422。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<CreateOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        String orderNo = orderApplicationService.createOrder(
                request.userId(),
                request.productId(),
                request.skuId(),
                request.productName(),
                request.quantity(),
                request.unitPrice(),
                request.isSimulateRollbackFailure());
        return Result.ok(new CreateOrderResponse(orderNo));
    }
}
