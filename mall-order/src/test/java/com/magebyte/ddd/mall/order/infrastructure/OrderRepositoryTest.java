package com.magebyte.ddd.mall.order.infrastructure;

import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    }

    @Test
    void find_by_order_no_after_state_change() {
        Order order = Order.create(2L, new Address("李四", "13900139000", "福田区会展中心"));
        order.addItem(OrderItem.create(3L, 300L, "显示器", 1, Money.of("1599.00")));
        Order saved = orderRepository.save(order);

        saved.markPaid(Money.of("1599.00"), LocalDateTime.now());
        orderRepository.save(saved);

        Order found = orderRepository.findByOrderNo(saved.orderNo()).orElseThrow();
        assertEquals(OrderStatus.PAID, found.status());
        assertEquals(Money.of("1599.00"), found.paidAmount());
        // 乐观锁版本号随更新 +1（OptimisticLockerInnerInterceptor 生效的实证）
        assertEquals(saved.version() + 1, found.version());
    }
}
