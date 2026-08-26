package com.magebyte.ddd.mall.order.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态迁移矩阵：与订单需求文档的 8 条合法迁移逐条对齐，
 * 终态没有出边，跳级迁移一律拒绝。
 */
class OrderStatusTest {

    private static final Map<OrderStatus, List<OrderStatus>> LEGAL = Map.of(
            OrderStatus.PENDING_PAY, List.of(OrderStatus.PAID, OrderStatus.CANCELLED),
            OrderStatus.PAID, List.of(OrderStatus.SHIPPED, OrderStatus.REFUND_REQUESTED),
            OrderStatus.SHIPPED, List.of(OrderStatus.RECEIVED, OrderStatus.REFUND_REQUESTED),
            OrderStatus.REFUND_REQUESTED, List.of(OrderStatus.REFUNDED));

    @Test
    void allows_the_eight_legal_transitions() {
        LEGAL.forEach((from, targets) -> targets.forEach(to ->
                assertTrue(from.canTransitionTo(to), from + " -> " + to + " 应当合法")));
    }

    @Test
    void rejects_terminal_and_skip_transitions() {
        // 需求文档点名的三个非法迁移样例
        assertFalse(OrderStatus.RECEIVED.canTransitionTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PAID));
        assertFalse(OrderStatus.PENDING_PAY.canTransitionTo(OrderStatus.RECEIVED));
        // 三个终态没有任何出边
        for (OrderStatus to : OrderStatus.values()) {
            assertFalse(OrderStatus.RECEIVED.canTransitionTo(to));
            assertFalse(OrderStatus.CANCELLED.canTransitionTo(to));
            assertFalse(OrderStatus.REFUNDED.canTransitionTo(to));
        }
    }
}
