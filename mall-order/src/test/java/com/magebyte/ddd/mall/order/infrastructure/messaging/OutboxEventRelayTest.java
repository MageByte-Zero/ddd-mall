package com.magebyte.ddd.mall.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.event.DomainEvent;
import com.magebyte.ddd.mall.order.domain.event.DomainEventPublisher;
import com.magebyte.ddd.mall.order.domain.event.OrderCreatedEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderPaidEvent;
import com.magebyte.ddd.mall.order.infrastructure.persistence.OutboxEventDO;
import com.magebyte.ddd.mall.order.infrastructure.persistence.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 中继器单元测试（纯 Mockito，不起 Spring、不连 MySQL/RocketMQ）：
 * 把"捞行 → 发送 → 标记"这条链路的每一种结局钉死。
 *
 * <p>发送端口用 mock，可以精确注入"前 N 次抛异常""标记时崩溃"这类故障；
 * Mapper 用 mock，断言中继器写回的 status/retryCount/nextRetryAt 是否正确。
 */
class OutboxEventRelayTest {

    private OutboxEventMapper outboxMapper;
    private DomainEventPublisher eventPublisher;
    private OutboxEventRelay relay;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        outboxMapper = mock(OutboxEventMapper.class);
        eventPublisher = mock(DomainEventPublisher.class);
        relay = new OutboxEventRelay(outboxMapper, eventPublisher, objectMapper);
    }

    private OutboxEventDO pendingRow(DomainEvent event) throws Exception {
        OutboxEventDO row = new OutboxEventDO();
        row.setId(1L);
        row.setEventId(event.eventId());
        row.setAggregateType("Order");
        row.setAggregateId(event.orderNo());
        row.setEventType(event.eventName());
        row.setPayload(objectMapper.writeValueAsString(event));
        row.setStatus(OutboxEventDO.STATUS_PENDING);
        row.setRetryCount(0);
        return row;
    }

    private OutboxEventDO updateCapture() {
        ArgumentCaptor<OutboxEventDO> captor = ArgumentCaptor.forClass(OutboxEventDO.class);
        verify(outboxMapper).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    void pending_row_is_published_and_marked_sent() throws Exception {
        OrderCreatedEvent event = OrderCreatedEvent.raise("OD-UNIT-1", 9301L, LocalDateTime.now());
        when(outboxMapper.selectList(any())).thenReturn(List.of(pendingRow(event)));

        int sent = relay.relayOnce();

        assertEquals(1, sent);
        verify(eventPublisher).publishAll(any());
        OutboxEventDO update = updateCapture();
        assertEquals(OutboxEventDO.STATUS_SENT, update.getStatus());
        assertNotNull(update.getSentAt(), "标记 SENT 必须写 sent_at");
    }

    @Test
    void send_failure_keeps_row_pending_with_backoff_then_retry_succeeds() throws Exception {
        OrderPaidEvent event = OrderPaidEvent.raise("OD-UNIT-2", Money.of("1599.00"), LocalDateTime.now());
        when(outboxMapper.selectList(any())).thenReturn(List.of(pendingRow(event)));
        // 第一次发送抛异常（模拟 broker 短暂不可用），第二次成功
        doThrow(new RuntimeException("broker 暂时不可用"))
                .doNothing()
                .when(eventPublisher).publishAll(any());

        // 第一轮：发送失败，行必须保持 PENDING，retry_count +1，退避时间写入
        assertEquals(0, relay.relayOnce());
        OutboxEventDO firstUpdate = updateCapture();
        assertEquals(OutboxEventDO.STATUS_PENDING, firstUpdate.getStatus());
        assertEquals(1, firstUpdate.getRetryCount());
        assertNotNull(firstUpdate.getNextRetryAt(), "失败后必须写 next_retry_at 退避");
        assertTrue(firstUpdate.getNextRetryAt().isAfter(LocalDateTime.now()),
                "退避时间必须在未来");
        assertNull(firstUpdate.getSentAt(), "未发送成功不能写 sent_at");

        // 第二轮（退避到期后该行再次被捞出）：发送成功，行变 SENT
        assertEquals(1, relay.relayOnce());
        verify(eventPublisher, times(2)).publishAll(any());
        ArgumentCaptor<OutboxEventDO> captor = ArgumentCaptor.forClass(OutboxEventDO.class);
        verify(outboxMapper, times(2)).updateById(captor.capture());
        OutboxEventDO secondUpdate = captor.getValue();
        assertEquals(OutboxEventDO.STATUS_SENT, secondUpdate.getStatus());
        assertNotNull(secondUpdate.getSentAt());
    }

    @Test
    void crash_between_send_and_mark_causes_duplicate_delivery() throws Exception {
        // 复现"发送成功、标记前崩溃"：MQ 已收到消息，但库里行还是 PENDING。
        // 下一轮中继会把同一事件再发一次——消息重复但不丢（at-least-once），
        // 去重交给消费端幂等（第 14 讲）。
        OrderCreatedEvent event = OrderCreatedEvent.raise("OD-UNIT-3", 9303L, LocalDateTime.now());
        when(outboxMapper.selectList(any())).thenReturn(List.of(pendingRow(event)));
        // 第一轮：发送成功后，标记 SENT 时数据库异常（等价于进程在这两步之间崩溃）
        doThrow(new RuntimeException("标记时连接中断"))
                .doReturn(1)
                .when(outboxMapper).updateById(any(OutboxEventDO.class));

        assertThrows(RuntimeException.class, relay::relayOnce,
                "标记失败应让本轮中断（保守处理，剩余行下一轮再捞）");
        // 重启后第二轮：行还是 PENDING，再次被捞出 → 同一事件第二次发送
        assertEquals(1, relay.relayOnce());

        ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher, times(2)).publishAll(events.capture());
        String firstId = events.getAllValues().get(0).get(0).eventId();
        String secondId = events.getAllValues().get(1).get(0).eventId();
        assertEquals(firstId, secondId, "同一个 eventId 被投递两次 = 重复投递（at-least-once）");
    }

    @Test
    void retries_exhausted_marks_failed() throws Exception {
        OrderCreatedEvent event = OrderCreatedEvent.raise("OD-UNIT-4", 9304L, LocalDateTime.now());
        OutboxEventDO row = pendingRow(event);
        row.setRetryCount(OutboxEventRelay.MAX_RETRIES - 1); // 再失败一次就到上限
        when(outboxMapper.selectList(any())).thenReturn(List.of(row));
        doThrow(new RuntimeException("broker 持续不可用")).when(eventPublisher).publishAll(any());

        relay.relayOnce();

        OutboxEventDO update = updateCapture();
        assertEquals(OutboxEventDO.STATUS_FAILED, update.getStatus(),
                "重试耗尽必须置 FAILED，不再被自动捞取");
        assertEquals(OutboxEventRelay.MAX_RETRIES, update.getRetryCount());
        assertNull(update.getNextRetryAt());
    }

    @Test
    void poison_payload_marked_failed_without_publish() {
        OutboxEventDO row = new OutboxEventDO();
        row.setId(5L);
        row.setEventId("evt-poison");
        row.setAggregateType("Order");
        row.setAggregateId("OD-UNIT-5");
        row.setEventType(OrderCreatedEvent.NAME);
        row.setPayload("{ 这不是合法 JSON");
        row.setStatus(OutboxEventDO.STATUS_PENDING);
        row.setRetryCount(0);
        when(outboxMapper.selectList(any())).thenReturn(List.of(row));

        relay.relayOnce();

        verify(eventPublisher, never()).publishAll(any());
        OutboxEventDO update = updateCapture();
        assertEquals(OutboxEventDO.STATUS_FAILED, update.getStatus(),
                "payload 损坏重试无意义，直接 FAILED（毒消息不占轮询）");
    }

    @Test
    void backoff_grows_exponentially_and_is_capped() {
        assertEquals(Duration.ofSeconds(5), OutboxEventRelay.backoff(1));
        assertEquals(Duration.ofSeconds(10), OutboxEventRelay.backoff(2));
        assertEquals(Duration.ofSeconds(20), OutboxEventRelay.backoff(3));
        assertEquals(Duration.ofSeconds(40), OutboxEventRelay.backoff(4));
        assertEquals(Duration.ofMinutes(5), OutboxEventRelay.backoff(8), "退避封顶 5 分钟");
        assertEquals(Duration.ofMinutes(5), OutboxEventRelay.backoff(20));
    }
}
