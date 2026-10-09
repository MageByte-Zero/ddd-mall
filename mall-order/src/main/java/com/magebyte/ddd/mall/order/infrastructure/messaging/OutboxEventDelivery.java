package com.magebyte.ddd.mall.order.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.order.domain.event.DomainEvent;
import com.magebyte.ddd.mall.order.domain.event.DomainEventPublisher;
import com.magebyte.ddd.mall.order.domain.event.OrderCancelledEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderCreatedEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderPaidEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderReceivedEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderShippedEvent;
import com.magebyte.ddd.mall.order.infrastructure.persistence.OutboxEventDO;
import com.magebyte.ddd.mall.order.infrastructure.persistence.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.apache.seata.spring.annotation.GlobalLock;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 一行一次本地事务；AT 全局锁检查通过后才发送。 */
@Component
public class OutboxEventDelivery {
    private static final Logger log = LoggerFactory.getLogger(OutboxEventDelivery.class);

    /** 最大重试次数：超过后行置 FAILED，不再自动重试，等待人工介入。 */
    static final int MAX_RETRIES = 5;

    /** 退避基数：第 1 次失败后等 5 秒，之后翻倍，封顶 5 分钟。 */
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(5);
    private static final Duration BACKOFF_CAP = Duration.ofMinutes(5);

    /** 事件名 → 事件 record 类型：中继器只认 JSON 和事件名，不认识聚合。 */
    private static final Map<String, Class<? extends DomainEvent>> EVENT_TYPES = Map.of(
            OrderCreatedEvent.NAME, OrderCreatedEvent.class,
            OrderPaidEvent.NAME, OrderPaidEvent.class,
            OrderShippedEvent.NAME, OrderShippedEvent.class,
            OrderReceivedEvent.NAME, OrderReceivedEvent.class,
            OrderCancelledEvent.NAME, OrderCancelledEvent.class);

    private final OutboxEventMapper outboxMapper;
    private final DomainEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public OutboxEventDelivery(OutboxEventMapper outboxMapper,
                            DomainEventPublisher eventPublisher,
                            ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @GlobalLock(lockRetryInterval = 100, lockRetryTimes = 3)
    @Transactional
    public boolean deliver(long id) {
        OutboxEventDO row = outboxMapper.lockById(id);
        if (row == null || !OutboxEventDO.STATUS_PENDING.equals(row.getStatus())
                || (row.getNextRetryAt() != null && row.getNextRetryAt().isAfter(LocalDateTime.now()))) return false;
        DomainEvent event;
        try {
            event = toDomainEvent(row);
        } catch (JsonProcessingException e) {
            // payload 无法反序列化：重试再多次结果都一样，直接 FAILED，避免毒消息占住轮询
            log.error("Outbox 事件 payload 损坏，标记 FAILED: id={}, eventId={}, eventType={}",
                    row.getId(), row.getEventId(), row.getEventType(), e);
            markFailed(row);
            return false;
        }
        try {
            // 先发：复用第 8 讲的 RocketMQ 发布路径（topic/tag/keys/JSON 转换都在那里）
            eventPublisher.publishAll(List.of(event));
        } catch (Exception e) {
            // 发送失败（broker 宕机、网络抖动）：行保持 PENDING，退避后下一轮重试
            log.warn("Outbox 事件投递失败，将退避重试: id={}, eventId={}, retryCount={}",
                    row.getId(), row.getEventId(), row.getRetryCount(), e);
            markRetryBackoff(row);
            return false;
        }
        // 后标记：这一步之前崩溃，行还是 PENDING，下一轮会重发（消息重复但不丢）
        markSent(row);
        log.info("Outbox 事件已投递: id={}, eventId={}, eventType={}, orderNo={}",
                row.getId(), row.getEventId(), row.getEventType(), row.getAggregateId());
        return true;
    }

    private DomainEvent toDomainEvent(OutboxEventDO row) throws JsonProcessingException {
        Class<? extends DomainEvent> type = EVENT_TYPES.get(row.getEventType());
        if (type == null) {
            // 未知事件名：本 BC 不认识，重试无意义，直接 FAILED
            throw new JsonProcessingException("未知事件类型: " + row.getEventType()) {
            };
        }
        DomainEvent event = objectMapper.treeToValue(objectMapper.readTree(row.getPayload()), type);
        if (!row.getEventId().equals(event.eventId()) || !row.getEventType().equals(event.eventName())
                || !row.getAggregateId().equals(event.orderNo())) {
            throw new JsonProcessingException("Outbox 行身份与事件载荷不一致") {};
        }
        return event;
    }

    private void markSent(OutboxEventDO row) {
        OutboxEventDO update = new OutboxEventDO();
        update.setId(row.getId());
        update.setStatus(OutboxEventDO.STATUS_SENT);
        update.setSentAt(LocalDateTime.now());
        // 不重置 next_retry_at：MyBatis-Plus 更新忽略 null 字段，且 SENT 行不会再被捞取，无影响
        outboxMapper.updateById(update);
    }

    private void markRetryBackoff(OutboxEventDO row) {
        int retries = row.getRetryCount() == null ? 0 : row.getRetryCount();
        int next = retries + 1;
        OutboxEventDO update = new OutboxEventDO();
        update.setId(row.getId());
        update.setRetryCount(next);
        if (next >= MAX_RETRIES) {
            update.setStatus(OutboxEventDO.STATUS_FAILED);
            update.setNextRetryAt(null);
            log.error("Outbox 事件重试 {} 次仍失败，标记 FAILED: id={}, eventId={}",
                    next, row.getId(), row.getEventId());
        } else {
            update.setStatus(OutboxEventDO.STATUS_PENDING);
            update.setNextRetryAt(LocalDateTime.now().plus(backoff(next)));
        }
        outboxMapper.updateById(update);
    }

    private void markFailed(OutboxEventDO row) {
        OutboxEventDO update = new OutboxEventDO();
        update.setId(row.getId());
        update.setStatus(OutboxEventDO.STATUS_FAILED);
        update.setNextRetryAt(null);
        outboxMapper.updateById(update);
    }

    /** 指数退避：5s、10s、20s、40s……封顶 5 分钟。 */
    static Duration backoff(int retryCount) {
        long seconds = BACKOFF_BASE.getSeconds() * (1L << (retryCount - 1));
        return Duration.ofSeconds(Math.min(seconds, BACKOFF_CAP.getSeconds()));
    }
}
