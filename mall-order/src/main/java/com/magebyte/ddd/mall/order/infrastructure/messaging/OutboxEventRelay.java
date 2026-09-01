package com.magebyte.ddd.mall.order.infrastructure.messaging;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Outbox 中继器（Message Relay）：定时把 outbox 表里 PENDING 的事件搬到 RocketMQ。
 *
 * <p>它是 Outbox 模式的第二个角色（第一个角色是仓储在业务事务内写 outbox 行）：
 * <ol>
 *   <li>定时捞一批 status=PENDING 且退避已到期的行（按 id 升序，即事件产生顺序）；</li>
 *   <li>把 payload JSON 还原成领域事件，交给第 8 讲的发布端口 {@link DomainEventPublisher}
 *       发往 RocketMQ——发送链路与 afterCommit 时期完全复用；</li>
 *   <li>发送成功才把行标记为 SENT；发送失败则 retry_count 加一并按指数退避写
 *       next_retry_at，行保持 PENDING，下一轮继续捞，事件不丢。</li>
 * </ol>
 *
 * <p>顺序很关键：<b>先发、后标记</b>。反过来（先标记后发）一旦标记完进程崩溃，
 * 事件永久丢失；先发后标记最坏情况是"发成功了但没来得及标记"，下一轮重发——
 * 消息可能重复但不会丢，即至少投递一次（at-least-once）。重复投递要求消费端
 * 按事件 ID 幂等，是第 14 讲的主题。
 *
 * <p>教学简化：单实例定时轮询。生产环境多实例部署时，多个中继会捞到同一批行
 * 造成重复发送，需要用 SELECT ... FOR UPDATE SKIP LOCKED 抢占（或专用调度锁）
 * 保证一行只被一个实例处理；轮询本身也可以换成 CDC（订阅 binlog，如 Debezium/canal）
 * 把延迟从秒级降到毫秒级——本讲不展开。
 */
@Component
public class OutboxEventRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRelay.class);

    /** 单轮最多捞多少行：控制一次轮询的事务/消息批量。 */
    private static final int BATCH_SIZE = 100;

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

    public OutboxEventRelay(OutboxEventMapper outboxMapper,
                            DomainEventPublisher eventPublisher,
                            ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * 定时轮询入口。fixedDelay：上一轮结束后再等 interval，避免轮询堆叠；
     * initialDelay 给应用启动留缓冲。间隔是"投递延迟 vs 数据库轮询压力"的取舍，
     * 配置项 ddd.outbox.relay-interval-ms，默认 2 秒。
     */
    @Scheduled(
            fixedDelayString = "${ddd.outbox.relay-interval-ms:2000}",
            initialDelayString = "${ddd.outbox.relay-initial-delay-ms:5000}")
    public void relayTick() {
        relayOnce();
    }

    /**
     * 执行一轮中继。包级公开是为了让集成测试精确控制时机（定时线程的调度时刻
     * 不可断言）；生产代码只走 {@link #relayTick()} 定时调用。
     *
     * @return 本轮成功投递的事件数
     */
    public int relayOnce() {
        List<OutboxEventDO> pending = fetchPending();
        int sent = 0;
        for (OutboxEventDO row : pending) {
            if (relayOne(row)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Outbox 中继完成: 本轮 {} 条事件已投递 (共捞取 {} 条)", sent, pending.size());
        }
        return sent;
    }

    private List<OutboxEventDO> fetchPending() {
        LocalDateTime now = LocalDateTime.now();
        return outboxMapper.selectList(Wrappers.<OutboxEventDO>lambdaQuery()
                .eq(OutboxEventDO::getStatus, OutboxEventDO.STATUS_PENDING)
                .and(w -> w.isNull(OutboxEventDO::getNextRetryAt)
                        .or().le(OutboxEventDO::getNextRetryAt, now))
                .orderByAsc(OutboxEventDO::getId)
                .last("LIMIT " + BATCH_SIZE));
    }

    /**
     * 投递一行。返回 true 表示成功标记 SENT。
     */
    private boolean relayOne(OutboxEventDO row) {
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
        return objectMapper.treeToValue(objectMapper.readTree(row.getPayload()), type);
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
