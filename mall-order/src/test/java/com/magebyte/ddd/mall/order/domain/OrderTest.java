package com.magebyte.ddd.mall.order.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order 聚合根：不变量由聚合自己守护，破坏不变量的调用全部被拒绝。
 */
class OrderTest {

    private static final Address ADDRESS = new Address("张三", "13800138000", "南山区科技园");

    private static OrderItem item(String name, int quantity, String unitPrice) {
        return OrderItem.create(1L, 100L, name, quantity, Money.of(unitPrice));
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
    void mark_paid_transitions_and_records_paid_amount() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        order.markPaid(Money.of("299.00"), LocalDateTime.now());
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(Money.of("299.00"), order.paidAmount());
    }

    @Test
    void mark_paid_rejects_wrong_amount() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.markPaid(Money.of("298.99"), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("金额守恒"));
    }

    @Test
    void mark_paid_rejects_empty_order() {
        Order order = Order.create(1L, ADDRESS);
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.markPaid(Money.zero(), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("空订单"));
    }

    @Test
    void mark_paid_twice_is_illegal_transition() {
        Order order = paidOrder();
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> order.markPaid(Money.of("299.00"), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("非法状态迁移"));
    }

    @Test
    void cancel_only_from_pending_pay() {
        Order order = Order.create(1L, ADDRESS);
        order.cancel(LocalDateTime.now());
        assertEquals(OrderStatus.CANCELLED, order.status());
        // CANCELLED 是终态：已取消不能变已支付
        assertThrows(OrderDomainException.class,
                () -> order.markPaid(Money.of("299.00"), LocalDateTime.now()));
    }

    @Test
    void cancel_paid_order_is_rejected() {
        Order order = paidOrder();
        assertThrows(OrderDomainException.class, () -> order.cancel(LocalDateTime.now()));
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
    void reconstitute_rejects_broken_amount_conservation() {
        OrderItem item = OrderItem.reconstitute(10L, 1L, 100L, "机械键盘", 1, Money.of("299.00"));
        // 落库数据说总金额是 200.00，但订单项小计是 299.00——重组时当场拒绝
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> Order.reconstitute(1L, "OD1", 1L, OrderStatus.PENDING_PAY,
                        List.of(item), Money.of("200.00"), null, ADDRESS, 0,
                        LocalDateTime.now(), LocalDateTime.now()));
        assertTrue(e.getMessage().contains("金额守恒"));
    }

    @Test
    void reconstitute_restores_consistent_aggregate() {
        OrderItem item = OrderItem.reconstitute(10L, 1L, 100L, "机械键盘", 2, Money.of("299.00"));
        Order order = Order.reconstitute(1L, "OD1", 1L, OrderStatus.PAID,
                List.of(item), Money.of("598.00"), Money.of("598.00"), ADDRESS, 1,
                LocalDateTime.now(), LocalDateTime.now());
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(Money.of("598.00"), order.totalAmount());
    }

    private static Order paidOrder() {
        Order order = Order.create(1L, ADDRESS);
        order.addItem(item("机械键盘", 1, "299.00"));
        order.markPaid(Money.of("299.00"), LocalDateTime.now());
        return order;
    }
}
