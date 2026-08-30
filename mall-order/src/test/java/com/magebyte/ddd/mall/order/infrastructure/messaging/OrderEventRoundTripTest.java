package com.magebyte.ddd.mall.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域事件真实 RocketMQ 往返测试：聚合保存 → 事务提交 → afterCommit 发送 →
 * 最小消费者收到并落入内存 sink。
 *
 * <p>不加 {@code @Transactional}：事件在 afterCommit 发送，事务必须真实提交，
 * 回滚型测试事务会把发送一起回滚掉。测试数据在 {@link AfterEach} 物理清理。
 */
@SpringBootTest
class OrderEventRoundTripTest {

    /** 需求文档验收线：状态变更后 5 秒内事件到达消费者。 */
    private static final long ACCEPTANCE_LIMIT_MS = 5_000;

    /** 测试等待上限（topic 首次自动创建、消费者拉取路由都需要一点时间）。 */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(15);

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DomainEventSink sink;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> createdOrderNos = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!createdOrderNos.isEmpty()) {
            String orderNoPlaceholders = String.join(",",
                    createdOrderNos.stream().map(x -> "?").toList());
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM t_order WHERE order_no IN (" + orderNoPlaceholders + ")",
                    Long.class, createdOrderNos.toArray());
            if (!ids.isEmpty()) {
                String idPlaceholders = String.join(",",
                        ids.stream().map(x -> "?").toList());
                jdbcTemplate.update("DELETE FROM t_order_status_history WHERE order_id IN ("
                        + idPlaceholders + ")", ids.toArray());
                jdbcTemplate.update("DELETE FROM t_order_item WHERE order_id IN ("
                        + idPlaceholders + ")", ids.toArray());
                jdbcTemplate.update("DELETE FROM t_order WHERE id IN ("
                        + idPlaceholders + ")", ids.toArray());
            }
            createdOrderNos.clear();
        }
        sink.clear();
    }

    @Test
    void order_created_event_reaches_consumer_within_5_seconds() throws Exception {
        sink.clear();
        Order order = Order.create(9101L, new Address("事件往返", "13800000001", "往返路 1 号"));
        order.addItem(OrderItem.create(1L, 100L, "机械键盘", 1, Money.of("299.00")));

        long started = System.currentTimeMillis();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Order saved = tx.execute(status -> orderRepository.save(order));
        createdOrderNos.add(saved.orderNo());

        ReceivedOrderEvent event = sink.awaitByTag("OrderCreated", WAIT_LIMIT)
                .orElseThrow(() -> new AssertionError("等待窗口内未收到 OrderCreated 事件"));

        long elapsed = event.receivedAt().toEpochMilli() - started;
        System.out.println("[往返实测] OrderCreated 从事务开始到消费者收到耗时 " + elapsed + " ms");
        assertTrue(elapsed <= ACCEPTANCE_LIMIT_MS,
                "OrderCreated 到达耗时 " + elapsed + "ms，超过需求文档 5 秒验收线");

        JsonNode body = objectMapper.readTree(event.jsonBody());
        assertEquals("OrderCreated", body.get("eventName").asText());
        assertEquals(1, body.get("schemaVersion").asInt());
        assertEquals(saved.orderNo(), body.get("orderNo").asText());
        assertEquals(9101L, body.get("userId").asLong());
        assertFalse(body.get("eventId").asText().isBlank());
        // 消息 keys 就是事件 ID，链路上可以按事件反查
        assertEquals(body.get("eventId").asText(), event.keys());
    }

    @Test
    void order_paid_event_carries_paid_amount_and_reaches_consumer_within_5_seconds()
            throws Exception {
        sink.clear();
        Order order = Order.create(9102L, new Address("事件往返", "13800000002", "往返路 2 号"));
        order.addItem(OrderItem.create(2L, 200L, "显示器", 1, Money.of("1599.00")));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Order saved = tx.execute(status -> orderRepository.save(order));
        createdOrderNos.add(saved.orderNo());
        sink.awaitByTag("OrderCreated", WAIT_LIMIT)
                .orElseThrow(() -> new AssertionError("前置条件：OrderCreated 未到达"));
        sink.clear();

        saved.markPaid(Money.of("1599.00"), "payment-callback", LocalDateTime.now());
        long started = System.currentTimeMillis();
        tx.executeWithoutResult(status -> orderRepository.save(saved));

        ReceivedOrderEvent event = sink.awaitByTag("OrderPaid", WAIT_LIMIT)
                .orElseThrow(() -> new AssertionError("等待窗口内未收到 OrderPaid 事件"));

        long elapsed = event.receivedAt().toEpochMilli() - started;
        System.out.println("[往返实测] OrderPaid 从事务开始到消费者收到耗时 " + elapsed + " ms");
        assertTrue(elapsed <= ACCEPTANCE_LIMIT_MS,
                "OrderPaid 到达耗时 " + elapsed + "ms，超过需求文档 5 秒验收线");

        JsonNode body = objectMapper.readTree(event.jsonBody());
        assertEquals("OrderPaid", body.get("eventName").asText());
        assertEquals(saved.orderNo(), body.get("orderNo").asText());
        assertEquals(0, new BigDecimal("1599.00")
                .compareTo(body.get("paidAmount").get("amount").decimalValue()));
        assertEquals(body.get("eventId").asText(), event.keys());
    }

    @Test
    void rolled_back_transaction_publishes_no_ghost_event() throws Exception {
        sink.clear();
        Order order = Order.create(9103L, new Address("事件往返", "13800000003", "往返路 3 号"));
        order.addItem(OrderItem.create(3L, 300L, "鼠标垫", 1, Money.of("19.90")));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            orderRepository.save(order);
            // 模拟业务失败：事务标记回滚，afterCommit 绝不允许触发
            status.setRollbackOnly();
        });

        // 给足 5 秒观察窗口：幽灵事件若存在，提交后几百毫秒内就该到达
        Thread.sleep(ACCEPTANCE_LIMIT_MS);
        assertTrue(sink.all().isEmpty(),
                "事务回滚后消费者不应收到任何事件，实际收到: "
                        + sink.all().stream().map(ReceivedOrderEvent::tag).toList());
        // 库里同样没有这笔订单：事务回滚与消息缺席互相印证
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order WHERE user_id = ?", Integer.class, 9103L);
        assertEquals(0, count);
    }
}
