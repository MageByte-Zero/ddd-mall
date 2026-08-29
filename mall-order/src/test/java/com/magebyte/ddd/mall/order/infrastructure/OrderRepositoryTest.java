package com.magebyte.ddd.mall.order.infrastructure;

import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import com.magebyte.ddd.mall.order.domain.StatusChange;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储集成测试：聚合经依赖倒置注入的实现，真实写入本地 MySQL 再读回。
 * 事务回滚保证测试不残留数据。
 */
@SpringBootTest
@Transactional
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void save_new_order_then_load_back_whole_aggregate() {
        Order order = Order.create(1L, new Address("张三", "13800138000", "南山区科技园"));
        order.addItem(OrderItem.create(1L, 100L, "机械键盘", 2, Money.of("299.00")));
        order.addItem(OrderItem.create(2L, 200L, "鼠标垫", 1, Money.of("19.90")));

        Order saved = orderRepository.save(order);

        assertNotNull(saved.id());
        Optional<Order> loaded = orderRepository.findById(saved.id());
        assertTrue(loaded.isPresent());
        Order found = loaded.get();
        assertEquals(saved.orderNo(), found.orderNo());
        assertEquals(OrderStatus.PENDING_PAY, found.status());
        assertEquals(Money.of("617.90"), found.totalAmount());
        assertEquals(2, found.getItems().size());
        assertEquals("机械键盘", found.getItems().get(0).productName());
        assertEquals(Money.of("598.00"), found.getItems().get(0).subtotal());
        assertEquals("张三", found.address().receiverName());
        // 创建即有第一节历史：from 为空、to=PENDING_PAY
        assertEquals(1, found.statusHistory().size());
        assertNull(found.statusHistory().get(0).from());
        assertEquals(OrderStatus.PENDING_PAY, found.statusHistory().get(0).to());
    }

    @Test
    void find_by_order_no_after_state_change() {
        Order order = Order.create(2L, new Address("李四", "13900139000", "福田区会展中心"));
        order.addItem(OrderItem.create(3L, 300L, "显示器", 1, Money.of("1599.00")));
        Order saved = orderRepository.save(order);

        saved.markPaid(Money.of("1599.00"), "payment-callback", LocalDateTime.now());
        orderRepository.save(saved);

        Order found = orderRepository.findByOrderNo(saved.orderNo()).orElseThrow();
        assertEquals(OrderStatus.PAID, found.status());
        assertEquals(Money.of("1599.00"), found.paidAmount());
        // 乐观锁版本号随更新 +1（OptimisticLockerInnerInterceptor 生效的实证）
        assertEquals(saved.version() + 1, found.version());
        // 支付这一步落了第二节历史，原因与操作人都在
        assertEquals(2, found.statusHistory().size());
        assertEquals(OrderStatus.PAID, found.statusHistory().get(1).to());
        assertEquals("支付回调成功", found.statusHistory().get(1).reason());
        assertEquals("payment-callback", found.statusHistory().get(1).operatedBy());
    }

    @Test
    void full_lifecycle_persists_four_history_changes() {
        Order order = Order.create(3L, new Address("王五", "13700137000", "天河区珠江新城"));
        order.addItem(OrderItem.create(4L, 400L, "机械键盘", 1, Money.of("299.00")));
        Order saved = orderRepository.save(order);                                // PENDING_PAY

        saved.markPaid(Money.of("299.00"), "payment-callback", LocalDateTime.now());
        saved = orderRepository.save(saved);                                      // PAID
        saved.markShipped("merchant:1", LocalDateTime.now());
        saved = orderRepository.save(saved);                                      // SHIPPED
        saved.confirmReceived("user:3", LocalDateTime.now());
        saved = orderRepository.save(saved);                                      // RECEIVED

        Order found = orderRepository.findById(saved.id()).orElseThrow();
        assertEquals(OrderStatus.RECEIVED, found.status());
        List<StatusChange> history = found.statusHistory();
        // 每次 save 只追加新增的尾段，历史不重复、不丢失
        assertEquals(4, history.size());
        assertNull(history.get(0).from());
        assertEquals(OrderStatus.PENDING_PAY, history.get(0).to());
        assertEquals(OrderStatus.PAID, history.get(1).to());
        assertEquals(OrderStatus.SHIPPED, history.get(2).to());
        assertEquals(OrderStatus.RECEIVED, history.get(3).to());
        for (int i = 1; i < history.size(); i++) {
            assertEquals(history.get(i - 1).to(), history.get(i).from());
        }
    }
}
