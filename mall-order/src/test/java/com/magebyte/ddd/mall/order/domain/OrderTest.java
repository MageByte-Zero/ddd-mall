package com.magebyte.ddd.mall.order.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order 聚合根：不变量由聚合自己守护，破坏不变量的调用全部被拒绝。
 * 状态机相关用例覆盖：生命周期历史链、非法迁移拦截、重组时历史反向校验。
 */
class OrderTest {

    private static final Address ADDRESS = new Address("张三", "13800138000", "南山区科技园");

    private static OrderItem item(String name, int quantity, String unitPrice) {
        return OrderItem.create(1L, 100L, name, quantity, Money.of(unitPrice));
    }

    /** 直接拼一条状态历史（供重组测试用，绕过聚合的迁移入口）。 */
    private static List<StatusChange> history(OrderStatus... toStates) {
        List<StatusChange> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        OrderStatus from = null;
        for (OrderStatus to : toStates) {
            list.add(new StatusChange(from, to, "测试原因", "tester:1", now));
            from = to;
        }
        return list;
    }

    @Test
    void create_starts_at_pending_pay_with_zero_total() {
        Order order = Order.create(1L, ADDRESS);
        assertEquals(OrderStatus.PENDING_PAY, order.status());
        assertEquals(Money.zero(), order.totalAmount());
        assertTrue(order.getItems().isEmpty());
        assertNotNull(order.orderNo());
        assertTrue(order.orderNo().startsWith("OD"));
        assertNull(order.paidAmount());
    }

    @Test
    void create_rejects_null_arguments() {
        assertThrows(NullPointerException.class, () -> Order.create(null, ADDRESS));
        assertThrows(NullPointerException.class, () -> Order.create(1L, null));
    }

    @Test
    void add_item_keeps_total_amount_conserved() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 2, "299.00"));
        order.addItem(item("鼠标垫", 1, "19.90"));
        // 金额守恒：总金额永远等于订单项小计之和
        assertEquals(Money.of("617.90"), order.totalAmount());
    }

    @Test
    void rejects_zero_or_negative_quantity() {
        assertThrows(OrderDomainException.class,
                () -> OrderItem.create(1L, 100L, "键盘", 0, Money.of("99.00")));
        assertThrows(OrderDomainException.class,
                () -> OrderItem.create(1L, 100L, "键盘", -1, Money.of("99.00")));
    }

    @Test
    void full_lifecycle_records_four_history_changes() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        order.markPaid(Money.of("299.00"), "payment-callback", LocalDateTime.now());
        order.markShipped("merchant:1", LocalDateTime.now());
        order.confirmReceived("user:1", LocalDateTime.now());

        assertEquals(OrderStatus.RECEIVED, order.status());
        assertEquals(Money.of("299.00"), order.paidAmount());

        List<StatusChange> changes = order.statusHistory();
        assertEquals(4, changes.size());
        // 第一节是创建记录：from 为空
        assertNull(changes.get(0).from());
        assertEquals(OrderStatus.PENDING_PAY, changes.get(0).to());
        assertEquals(OrderStatus.PAID, changes.get(1).to());
        assertEquals(OrderStatus.SHIPPED, changes.get(2).to());
        assertEquals(OrderStatus.RECEIVED, changes.get(3).to());
        // 历史链首尾相接：每节 from 等于上一节 to
        for (int i = 1; i < changes.size(); i++) {
            assertEquals(changes.get(i - 1).to(), changes.get(i).from());
        }
        // 原因和操作人随迁移落链
        assertEquals("商家发货", changes.get(2).reason());
        assertEquals("merchant:1", changes.get(2).operatedBy());
    }

    @Test
    void mark_paid_transitions_and_records_paid_amount() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        order.markPaid(Money.of("299.00"), "payment-callback", LocalDateTime.now());
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(Money.of("299.00"), order.paidAmount());
    }

    @Test
    void mark_paid_rejects_wrong_amount() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.markPaid(Money.of("298.99"), "payment-callback", LocalDateTime.now()));
        assertTrue(e.getMessage().contains("金额守恒"));
    }

    @Test
    void mark_paid_rejects_empty_order() {
        Order order = Order.create(1L, ADDRESS);
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.markPaid(Money.zero(), "payment-callback", LocalDateTime.now()));
        assertTrue(e.getMessage().contains("空订单"));
    }

    @Test
    void mark_paid_twice_is_illegal_transition() {
        Order order = paidOrder();
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.markPaid(Money.of("299.00"), "payment-callback", LocalDateTime.now()));
        assertTrue(e.getMessage().contains("非法状态迁移"));
    }

    @Test
    void ship_before_pay_is_rejected() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        // 待支付直接发货：跳级迁移，迁移表拒绝
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.markShipped("merchant:1", LocalDateTime.now()));
        assertTrue(e.getMessage().contains("非法状态迁移"));
    }

    @Test
    void receive_before_ship_is_rejected() {
        Order order = paidOrder();
        // 已支付未发货就确认收货：跳级迁移，迁移表拒绝
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.confirmReceived("user:1", LocalDateTime.now()));
        assertTrue(e.getMessage().contains("非法状态迁移"));
    }

    @Test
    void cancel_only_from_pending_pay() {
        Order order = Order.create(1L, ADDRESS);
        order.cancel("用户主动取消", "user:1", LocalDateTime.now());
        assertEquals(OrderStatus.CANCELLED, order.status());
        // CANCELLED 是终态：已取消不能变已支付
        assertThrows(OrderDomainException.class,
                () -> order.markPaid(Money.of("299.00"), "payment-callback", LocalDateTime.now()));
    }

    @Test
    void cancel_with_timeout_reason_is_recorded() {
        Order order = Order.create(1L, ADDRESS);
        order.cancel("超时未支付自动取消", "system:timeout-job", LocalDateTime.now());
        StatusChange last = order.statusHistory().get(1);
        assertEquals(OrderStatus.CANCELLED, last.to());
        assertEquals("超时未支付自动取消", last.reason());
        assertEquals("system:timeout-job", last.operatedBy());
    }

    @Test
    void cancel_paid_order_is_rejected() {
        Order order = paidOrder();
        // 已支付不能直接取消，只能走退款流程（第 15 讲）
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.cancel("用户主动取消", "user:1", LocalDateTime.now()));
        assertTrue(e.getMessage().contains("非法状态迁移"));
    }

    @Test
    void cancel_shipped_order_is_rejected() {
        Order order = shippedOrder();
        assertThrows(OrderDomainException.class,
                () -> order.cancel("用户主动取消", "user:1", LocalDateTime.now()));
    }

    @Test
    void items_are_frozen_after_payment() {
        Order order = paidOrder();
        assertThrows(OrderDomainException.class,
                () -> order.addItem(item("鼠标垫", 1, "19.90")));
    }

    @Test
    void get_items_returns_immutable_snapshot() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        List<OrderItem> snapshot = order.getItems();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(item("鼠标垫", 1, "19.90")));
        assertEquals(1, order.getItems().size()); // 快照改动不影响聚合内部
    }

    @Test
    void status_history_returns_immutable_snapshot() {
        Order order = paidOrder();
        List<StatusChange> snapshot = order.statusHistory();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new StatusChange(OrderStatus.PAID, OrderStatus.SHIPPED,
                        "商家发货", "merchant:1", LocalDateTime.now())));
    }

    @Test
    void reconstitute_rejects_broken_amount_conservation() {
        OrderItem item = OrderItem.reconstitute(10L, 1L, 100L, "机械键盘", 1, Money.of("299.00"));
        // 落库数据说总金额是 200.00，但订单项小计是 299.00——重组时当场拒绝
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> Order.reconstitute(1L, "OD1", 1L, OrderStatus.PENDING_PAY,
                        List.of(item), history(OrderStatus.PENDING_PAY),
                        Money.of("200.00"), null, ADDRESS, 0,
                        LocalDateTime.now(), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("金额守恒"));
    }

    @Test
    void reconstitute_rejects_empty_history() {
        OrderItem item = OrderItem.reconstitute(10L, 1L, 100L, "机械键盘", 1, Money.of("299.00"));
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> Order.reconstitute(1L, "OD1", 1L, OrderStatus.PENDING_PAY,
                        List.of(item), List.of(),
                        Money.of("299.00"), null, ADDRESS, 0,
                        LocalDateTime.now(), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("状态历史为空"));
    }

    @Test
    void reconstitute_rejects_broken_history_chain() {
        OrderItem item = OrderItem.reconstitute(10L, 1L, 100L, "机械键盘", 1, Money.of("299.00"));
        // 历史第二节从 PAID 起跳，但上一节停在 PENDING_PAY——断链
        List<StatusChange> broken = new ArrayList<>(history(OrderStatus.PENDING_PAY));
        broken.add(new StatusChange(OrderStatus.PAID, OrderStatus.RECEIVED,
                "测试原因", "tester:1", LocalDateTime.now()));
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> Order.reconstitute(1L, "OD1", 1L, OrderStatus.RECEIVED,
                        List.of(item), broken,
                        Money.of("299.00"), Money.of("299.00"), ADDRESS, 1,
                        LocalDateTime.now(), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("断链"));
    }

    @Test
    void reconstitute_rejects_illegal_transition_in_history() {
        OrderItem item = OrderItem.reconstitute(10L, 1L, 100L, "机械键盘", 1, Money.of("299.00"));
        // 链条首尾相接，但 PENDING_PAY → SHIPPED 是非法迁移
        List<StatusChange> illegal = new ArrayList<>(history(OrderStatus.PENDING_PAY));
        illegal.add(new StatusChange(OrderStatus.PENDING_PAY, OrderStatus.SHIPPED,
                "测试原因", "tester:1", LocalDateTime.now()));
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> Order.reconstitute(1L, "OD1", 1L, OrderStatus.SHIPPED,
                        List.of(item), illegal,
                        Money.of("299.00"), Money.of("299.00"), ADDRESS, 1,
                        LocalDateTime.now(), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("状态机守恒"));
    }

    @Test
    void reconstitute_rejects_history_not_matching_current_status() {
        OrderItem item = OrderItem.reconstitute(10L, 1L, 100L, "机械键盘", 1, Money.of("299.00"));
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> Order.reconstitute(1L, "OD1", 1L, OrderStatus.PAID,
                        List.of(item), history(OrderStatus.PENDING_PAY),
                        Money.of("299.00"), null, ADDRESS, 0,
                        LocalDateTime.now(), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("末节"));
    }

    @Test
    void reconstitute_restores_consistent_aggregate() {
        OrderItem item = OrderItem.reconstitute(10L, 1L, 100L, "机械键盘", 2, Money.of("299.00"));
        Order order = Order.reconstitute(1L, "OD1", 1L, OrderStatus.PAID,
                List.of(item), history(OrderStatus.PENDING_PAY, OrderStatus.PAID),
                Money.of("598.00"), Money.of("598.00"), ADDRESS, 1,
                LocalDateTime.now(), LocalDateTime.now());
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(Money.of("598.00"), order.totalAmount());
        assertEquals(2, order.statusHistory().size());
    }

    private static Order paidOrder() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        order.markPaid(Money.of("299.00"), "payment-callback", LocalDateTime.now());
        return order;
    }

    private static Order shippedOrder() {
        Order order = paidOrder();
        order.markShipped("merchant:1", LocalDateTime.now());
        return order;
    }
}
