package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.StatusChange;

import java.util.List;

/**
 * 订单详情：查询用例返回的应用层读模型。
 *
 * <p>为什么不直接把 {@link Order} 聚合返回给接口层：聚合里有{@code version}、
 * 聚合内的事件快照、以及"必须用迁移方法才能改"的封装语义，直接序列化出去，
 * 等于把内部结构变成对外 API——下次重构聚合就要改契约。读模型反过来：
 * 它是为"这一次查询长什么样"服务的，字段随展示需要增减，不影响领域层。
 *
 * <p>本讲只做最简单的同构投影（CQRS 的读模型分离在后续讲次展开）。
 */
public record OrderDetail(
        String orderNo,
        Long userId,
        String status,
        String totalAmount,
        String paidAmount,
        String receiverName,
        List<OrderItemView> items,
        List<StatusChangeView> history,
        Integer version) {

    /** 从领域对象投影成本视图。money → 字符串，避免 BigDecimal 的 scale 差异落到 API 上。 */
    public static OrderDetail from(Order order) {
        List<OrderItemView> itemViews = order.getItems().stream().map(OrderItemView::from).toList();
        List<StatusChangeView> historyViews =
                order.statusHistory().stream().map(StatusChangeView::from).toList();
        return new OrderDetail(
                order.orderNo(),
                order.userId(),
                order.status().name(),
                text(order.totalAmount()),
                order.paidAmount() == null ? null : text(order.paidAmount()),
                order.address().receiverName(),
                itemViews,
                historyViews,
                order.version());
    }

    private static String text(Money money) {
        return money == null ? null : money.amount().toPlainString();
    }

    /** 订单项视图：含单价与小计，便于调用方核对金额守恒。 */
    public record OrderItemView(Long productId, Long skuId, String productName,
                                int quantity, String unitPrice, String subtotal) {

        private static OrderItemView from(OrderItem item) {
            return new OrderItemView(item.productId(), item.skuId(), item.productName(),
                    item.quantity(), item.unitPrice().amount().toPlainString(),
                    item.subtotal().amount().toPlainString());
        }
    }

    /** 状态变更视图：from 为空的那一节就是订单的创建记录。 */
    public record StatusChangeView(String from, String to, String reason,
                                   String operatedBy, String occurredAt) {

        private static StatusChangeView from(StatusChange change) {
            return new StatusChangeView(
                    change.from() == null ? null : change.from().name(),
                    change.to().name(),
                    change.reason(),
                    change.operatedBy(),
                    change.occurredAt() == null ? null : change.occurredAt().toString());
        }
    }
}
