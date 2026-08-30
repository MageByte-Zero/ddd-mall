package com.magebyte.ddd.mall.order.domain.event;

import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域事件单测（纯 JUnit，不需要 Spring / 数据库 / 消息中间件）：
 * 聚合在什么时刻抛出什么事件、非法迁移不产事件、pullEvents 取后即空、
 * 重组历史不产事件。
 */
class OrderDomainEventTest {

    private Order newPendingOrderWithItem() {
        Order order = Order.create(8001L, new Address("事件测试", "13800000000", "测试路 1 号"));
        order.addItem(OrderItem.create(1L, 100L, "机械键盘", 1, Money.of("299.00")));
        return order;
    }

    @Test
    void create_raises_order_created_event_with_full_metadata() {
        Order order = Order.create(8001L, new Address("事件测试", "13800000000", "测试路 1 号"));

        List<DomainEvent> events = order.pullEvents();
        assertEquals(1, events.size());
        DomainEvent event = events.get(0);
        assertInstanceOf(OrderCreatedEvent.class, event);
        assertEquals("OrderCreated", event.eventName());
        assertEquals(1, event.schemaVersion());
        assertEquals(order.orderNo(), event.orderNo());
        assertNotNull(event.eventId());
        assertFalse(event.eventId().isBlank());
        assertNotNull(event.occurredOn());
        assertEquals(8001L, ((OrderCreatedEvent) event).userId());
    }

    @Test
    void each_transition_raises_its_own_event_with_payload() {
        LocalDateTime now = LocalDateTime.now();
        Order order = newPendingOrderWithItem();
        order.pullEvents(); // 丢掉创建事件，只观察迁移事件

        order.markPaid(Money.of("299.00"), "payment-callback", now);
        OrderPaidEvent paid = soleEvent(order, OrderPaidEvent.class);
        assertEquals("OrderPaid", paid.eventName());
        assertEquals(Money.of("299.00"), paid.paidAmount());
        assertEquals(order.orderNo(), paid.orderNo());

        order.markShipped("merchant:1", now.plusMinutes(1));
        OrderShippedEvent shipped = soleEvent(order, OrderShippedEvent.class);
        assertEquals("OrderShipped", shipped.eventName());
        assertEquals("merchant:1", shipped.operatedBy());

        order.confirmReceived("user:8001", now.plusDays(2));
        OrderReceivedEvent received = soleEvent(order, OrderReceivedEvent.class);
        assertEquals("OrderReceived", received.eventName());
        assertEquals("user:8001", received.operatedBy());

        Order cancelling = newPendingOrderWithItem();
        cancelling.pullEvents();
        cancelling.cancel("用户主动取消", "user:8002", now.plusHours(1));
        OrderCancelledEvent cancelled = soleEvent(cancelling, OrderCancelledEvent.class);
        assertEquals("OrderCancelled", cancelled.eventName());
        assertEquals("用户主动取消", cancelled.reason());
        assertEquals("user:8002", cancelled.operatedBy());
    }

    @Test
    void illegal_transition_raises_no_event() {
        Order order = newPendingOrderWithItem();
        order.pullEvents();

        // 待支付直接发货：迁移表不允许，聚合必须拒绝
        assertThrows(OrderDomainException.class,
                () -> order.markShipped("merchant:1", LocalDateTime.now()));
        // 非法迁移一个事件都不能产生——失败的业务动作不是"已发生事实"
        assertTrue(order.pullEvents().isEmpty());
    }

    @Test
    void pull_events_returns_snapshot_and_drains() {
        Order order = newPendingOrderWithItem();

        List<DomainEvent> first = order.pullEvents();
        assertEquals(1, first.size());
        // 第二次拉取为空：事件已被取走，不会重复发布
        assertTrue(order.pullEvents().isEmpty());
        // 返回的是不可变快照，外部改不到聚合内部
        assertThrows(UnsupportedOperationException.class, () -> first.add(null));
    }

    @Test
    void reconstituted_aggregate_raises_no_events() {
        Order order = newPendingOrderWithItem();
        order.markPaid(Money.of("299.00"), "payment-callback", LocalDateTime.now());

        Order rebuilt = Order.reconstitute(1L, order.orderNo(), 8001L, OrderStatus.PAID,
                order.getItems(), order.statusHistory(), order.totalAmount(),
                order.paidAmount(), order.address(), 0,
                order.createdAt(), order.updatedAt());

        // 重组是"还原历史"而不是"新发生事实"：事件在当初已发过，不能再发一遍
        assertTrue(rebuilt.pullEvents().isEmpty());
    }

    private <T extends DomainEvent> T soleEvent(Order order, Class<T> type) {
        List<DomainEvent> events = order.pullEvents();
        assertEquals(1, events.size(), "期望恰好 1 个事件，实际: " + events);
        assertInstanceOf(type, events.get(0));
        return type.cast(events.get(0));
    }
}
