package com.magebyte.ddd.mall.order.interfaces.rest;

/**
 * 创建订单响应体。
 *
 * @deprecated 第 11 讲起三个命令端点统一返回 {@link OrderDetailResponse}
 *             （一次调用拿到执行后的完整状态，不必再补一次查询）。
 *             保留到下一讲，供对照第 10 讲的接口形态，之后删除。
 */
@Deprecated
public record CreateOrderResponse(String orderNo) {
}
