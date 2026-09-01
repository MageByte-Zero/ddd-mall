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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbox 端到端集成测试：真实 MySQL + 真实 RocketMQ。
 *
 * <p>不加 {@code @Transactional}——本讲的核心就是"事务真实提交后 outbox 行落库、
 * 中继器再投递"，回滚型测试事务会把 outbox 行一起回滚，什么都测不到。
 * 测试数据在 {@link AfterEach} 物理清理（订单表 + outbox 表）。
 *
 * <p>定时轮询在测试配置里静音（test/resources/application-dev.yml 把间隔拉到 1 小时），
 * 每轮中继由测试手动调用 {@link OutboxEventRelay#relayOnce()} 精确驱动，
 * 不依赖定时线程的调度时刻。
 */
@SpringBootTest
class OutboxEventRoundTripTest {

    /** 需求文档验收线：状态变更后 5 秒内事件到达消费者。 */
    private static final long ACCEPTANCE_LIMIT_MS = 5_000;
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(15);

    @Autowired private OrderRepository orderRepository;
    @Autowired private DomainEventSink sink;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxEventRelay relay;
    @Autowired private TestFaultEventPublisher faultPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> createdOrderNos = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!createdOrderNos.isEmpty()) {
            String placeholders = String.join(",",
                    createdOrderNos.stream().map(x -> "?").toList());
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM t_order WHERE order_no IN (" + placeholders + ")",
                    Long.class, createdOrderNos.toArray());
            jdbcTemplate.update("DELETE FROM t_outbox_event WHERE aggregate_id IN ("
                    + placeholders + ")", createdOrderNos.toArray());
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
    void outbox_row_committed_with_order_and_relayed_to_mq() throws Exception {
        sink.clear();
        Order order = Order.create(9201L, new Address("Outbox 往返", "13800000011", "发件路 1 号"));
        order.addItem(OrderItem.create(1L, 100L, "机械键盘", 1, Money.of("299.00")));

        // 第一步：业务事务提交。提交后 outbox 行必须已落库且为 PENDING
        long committedAt = System.currentTimeMillis();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Order saved = tx.execute(status -> orderRepository.save(order));
        createdOrderNos.add(saved.orderNo());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_id, event_type, status, retry_count, payload, sent_at "
                        + "FROM t_outbox_event WHERE aggregate_id = ?", saved.orderNo());
        assertEquals("OrderCreated", row.get("event_type"));
        assertEquals("PENDING", row.get("status"), "提交后、中继前必须是 PENDING");
        assertEquals(0, ((Number) row.get("retry_count")).intValue());
        assertEquals(null, row.get("sent_at"), "未投递前 sent_at 必须为空");
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        assertEquals(saved.orderNo(), payload.get("orderNo").asText());
        assertEquals("OrderCreated", payload.get("eventName").asText());
        String eventId = (String) row.get("event_id");
        assertFalse(eventId.isBlank());

        // 第二步：手动驱动一轮中继（生产环境由 @Scheduled 每 2 秒触发）
        int sent = relay.relayOnce();
        assertEquals(1, sent);

        // 第三步：日志消费者真实收到事件（RocketMQ 端到端）
        ReceivedOrderEvent event = sink.awaitByTag("OrderCreated", WAIT_LIMIT)
                .orElseThrow(() -> new AssertionError("等待窗口内未收到 OrderCreated 事件"));
        long elapsed = event.receivedAt().toEpochMilli() - committedAt;
        System.out.println("[Outbox 往返] OrderCreated 从事务提交到消费者收到耗时 " + elapsed + " ms"
                + "（含手动触发中继；生产环境额外加一个轮询间隔 ≤2s）");
        assertTrue(elapsed <= ACCEPTANCE_LIMIT_MS,
                "OrderCreated 到达耗时 " + elapsed + "ms，超过需求文档 5 秒验收线");
        assertEquals(eventId, event.keys(), "消息 keys 必须等于 outbox 行 event_id");

        // 第四步：行标记为 SENT
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT status, sent_at FROM t_outbox_event WHERE event_id = ?", eventId);
        assertEquals("SENT", after.get("status"));
        assertNotNull(after.get("sent_at"), "投递成功必须写 sent_at");
    }

    @Test
    void rolled_back_transaction_leaves_no_outbox_row_and_no_message() throws Exception {
        sink.clear();
        Order order = Order.create(9202L, new Address("Outbox 回滚", "13800000012", "发件路 2 号"));
        order.addItem(OrderItem.create(2L, 200L, "显示器", 1, Money.of("1599.00")));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            orderRepository.save(order);
            status.setRollbackOnly();   // 模拟业务失败：事务回滚
        });

        // 同事务原子性：业务数据和 outbox 行一起消失
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order WHERE user_id = ?", Integer.class, 9202L);
        assertEquals(0, orderCount, "回滚后订单必须不存在");
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id = ?",
                Integer.class, order.orderNo());
        assertEquals(0, outboxCount, "回滚后 outbox 行必须随业务数据一起消失（无幽灵事件源头）");

        // 即使驱动中继，也没有任何东西可发；消费者 5 秒内零消息
        assertEquals(0, relay.relayOnce(), "回滚后中继器捞不到任何行");
        Thread.sleep(ACCEPTANCE_LIMIT_MS);
        assertTrue(sink.all().isEmpty(),
                "事务回滚后消费者不应收到任何事件，实际收到: "
                        + sink.all().stream().map(ReceivedOrderEvent::tag).toList());
    }

    @Test
    void send_failure_keeps_row_pending_with_retry_count_then_retry_succeeds() throws Exception {
        sink.clear();
        Order order = Order.create(9203L, new Address("Outbox 重试", "13800000013", "发件路 3 号"));
        order.addItem(OrderItem.create(3L, 300L, "鼠标垫", 1, Money.of("19.90")));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Order saved = tx.execute(status -> orderRepository.save(order));
        createdOrderNos.add(saved.orderNo());

        // 第一次中继：发送被注入故障打断。行必须保持 PENDING、retry_count 增长、事件不丢
        faultPublisher.failNext(1);
        assertEquals(0, relay.relayOnce(), "发送失败本轮投递数为 0");

        Map<String, Object> failed = jdbcTemplate.queryForMap(
                "SELECT status, retry_count, next_retry_at FROM t_outbox_event WHERE aggregate_id = ?",
                saved.orderNo());
        assertEquals("PENDING", failed.get("status"), "发送失败不能标记 SENT，行保持 PENDING");
        assertEquals(1, ((Number) failed.get("retry_count")).intValue(), "retry_count 必须 +1");
        assertNotNull(failed.get("next_retry_at"), "失败后必须写退避时间");
        assertTrue(sink.all().isEmpty(), "发送失败时消费者不应收到消息");

        // 退避未到期时中继器不应捞取该行（next_retry_at 在未来）
        assertEquals(0, relay.relayOnce(), "退避未到期不应重复捞取");

        // 模拟退避到期（生产环境等 5 秒指数退避；测试里直接把时间拨到过去）
        jdbcTemplate.update(
                "UPDATE t_outbox_event SET next_retry_at = ? WHERE aggregate_id = ?",
                LocalDateTime.now().minusSeconds(1), saved.orderNo());

        // 下一轮中继：发送恢复，事件成功投递，行变 SENT
        assertEquals(1, relay.relayOnce());
        ReceivedOrderEvent event = sink.awaitByTag("OrderCreated", WAIT_LIMIT)
                .orElseThrow(() -> new AssertionError("重试成功后消费者应收到 OrderCreated"));
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT status, retry_count, sent_at FROM t_outbox_event WHERE aggregate_id = ?",
                saved.orderNo());
        assertEquals("SENT", after.get("status"));
        assertEquals(1, ((Number) after.get("retry_count")).intValue(), "成功后 retry_count 不再增长");
        assertNotNull(after.get("sent_at"));
        assertEquals("OrderCreated", event.tag());
    }
}
