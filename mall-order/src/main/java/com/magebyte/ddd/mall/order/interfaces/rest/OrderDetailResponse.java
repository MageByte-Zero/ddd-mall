package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.order.application.OrderDetail;

import java.util.List;

/**
 * 订单详情响应体（第 11 讲引入，作为三个命令端点与查询端点的统一返回形状）。
 *
 * <p>为什么有了应用层的 {@link OrderDetail} 还要再写一层协议 DTO：
 * 应用层读模型跟着"这一次用例想告诉你什么"长，HTTP 响应跟着"对外 API 契约"长。
 * 今天两者字段一致，第 20 讲网关对外、第 26 讲契约测试接入之后，
 * 响应字段的增删不能反过来绑架领域层的重构。这一层映射是提前付的保险费。
 */
public record OrderDetailResponse(
        String orderNo,
        Long userId,
        String status,
        String totalAmount,
        String paidAmount,
        String receiverName,
        List<OrderItemView> items,
        List<StatusChangeView> history,
        Integer version) {

    public static OrderDetailResponse from(OrderDetail detail) {
        return new OrderDetailResponse(
                detail.orderNo(),
                detail.userId(),
                detail.status(),
                detail.totalAmount(),
                detail.paidAmount(),
                detail.receiverName(),
                detail.items().stream().map(OrderItemView::from).toList(),
                detail.history().stream().map(StatusChangeView::from).toList(),
                detail.version());
    }

    public record OrderItemView(Long productId, Long skuId, String productName,
                                int quantity, String unitPrice, String subtotal) {

        private static OrderItemView from(OrderDetail.OrderItemView view) {
            return new OrderItemView(view.productId(), view.skuId(), view.productName(),
                    view.quantity(), view.unitPrice(), view.subtotal());
        }
    }

    public record StatusChangeView(String from, String to, String reason,
                                   String operatedBy, String occurredAt) {

        private static StatusChangeView from(OrderDetail.StatusChangeView view) {
            return new StatusChangeView(view.from(), view.to(), view.reason(),
                    view.operatedBy(), view.occurredAt());
        }
    }
}
