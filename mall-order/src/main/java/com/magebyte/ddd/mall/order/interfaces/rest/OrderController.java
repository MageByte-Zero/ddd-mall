package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.order.application.CancelOrderCommand;
import com.magebyte.ddd.mall.order.application.CreateOrderCommand;
import com.magebyte.ddd.mall.order.application.OrderApplicationService;
import com.magebyte.ddd.mall.order.application.OrderDetail;
import com.magebyte.ddd.mall.order.application.PayOrderCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口层：订单三个核心用例（创建 / 支付 / 取消）+ 一个查询入口。
 *
 * <p>本类只做三件事：取出路径参数、把请求 DTO 翻译成应用层命令、把应用层读模型
 * 翻译成响应体。它<b>不判断任何业务规则</b>——"订单能不能支付"是状态机的事，
 * "钱够不够"是聚合的事。控制器里一旦出现 {@code if (order.getStatus() == ...)}
 * 就说明规则从领域层漏出来了。
 *
 * <p>三个命令端点统一返回"执行后的订单状态"（{@link OrderDetailResponse}）：
 * 调用方不需要为了知道结果再打一次查询接口，也让端到端测试可以在一次响应里
 * 同时断言状态、金额和历史链长度。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    /**
     * 创建订单（全局事务入口）。成功返回 201；库存不足等业务失败返回 422。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<OrderDetailResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderDetail detail = orderApplicationService.createOrder(new CreateOrderCommand(
                request.userId(),
                request.productId(),
                request.skuId(),
                request.productName(),
                request.quantity(),
                request.unitPrice(),
                request.receiverName(),
                request.receiverPhone(),
                request.receiverAddress(),
                request.isSimulateRollbackFailure()));
        return Result.ok(OrderDetailResponse.from(detail));
    }

    /**
     * 支付订单：第三方支付成功后的回调入口，PENDING_PAY → PAID。
     * 成功返回 200；金额不符、状态不允许支付返回 422；订单不存在返回 404。
     */
    @PostMapping("/{orderNo}/payment")
    @ResponseStatus(HttpStatus.OK)
    public Result<OrderDetailResponse> pay(@PathVariable String orderNo,
                                           @Valid @RequestBody PayOrderRequest request) {
        OrderDetail detail = orderApplicationService.payOrder(
                new PayOrderCommand(orderNo, request.paidAmount(), request.operatedBy()));
        return Result.ok(OrderDetailResponse.from(detail));
    }

    /**
     * 取消订单：PENDING_PAY → CANCELLED，并同步归还库存（全局事务）。
     * 成功返回 200；已支付等状态不允许取消返回 422；订单不存在返回 404。
     */
    @PostMapping("/{orderNo}/cancellation")
    @ResponseStatus(HttpStatus.OK)
    public Result<OrderDetailResponse> cancel(@PathVariable String orderNo,
                                              @Valid @RequestBody CancelOrderRequest request) {
        OrderDetail detail = orderApplicationService.cancelOrder(new CancelOrderCommand(
                orderNo, request.reason(), request.operatedBy(),
                request.isSimulateReleaseFailure()));
        return Result.ok(OrderDetailResponse.from(detail));
    }

    /**
     * 查询订单详情。不存在返回 404。
     */
    @GetMapping("/{orderNo}")
    @ResponseStatus(HttpStatus.OK)
    public Result<OrderDetailResponse> detail(@PathVariable String orderNo) {
        return Result.ok(OrderDetailResponse.from(orderApplicationService.getOrder(orderNo)));
    }
}
