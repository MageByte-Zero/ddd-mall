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
 * Outbox 中继器：轮询只获取候选行，由独立代理 bean 逐行事务投递。
 *
 * <p>候选查询不能证明 AT 全局事务已经提交。每行需当前读、检查全局锁和
 * 待发送状态，通过后才发送；SEND_OK 返回后才标 SENT。发送已成功但标记
 * 未提交时仍可重发原事件，因此消费端需要事件去重与稳定业务动作身份。
 *
 * <p>当前逐行锁可避免并发中继同时处理同一待发送行，但网络调用会占用
 * 数据库锁。租约调度、跨事件顺序和 CDC 留给后续演进。重试耗尽后置 FAILED，
 * 需要人工恢复；本实现不承诺掉电、毁盘或集群故障下绝对不丢消息。
 */
@Component
public class OutboxEventRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRelay.class);

    /** 单轮最多捞多少行：控制一次轮询的事务/消息批量。 */
    private static final int BATCH_SIZE = 100;

    /** 最大重试次数：超过后行置 FAILED，不再自动重试，等待人工介入。 */
    static final int MAX_RETRIES = 5;

    private final OutboxEventMapper outboxMapper;
    private final OutboxEventDelivery delivery;
    public OutboxEventRelay(OutboxEventMapper mapper, OutboxEventDelivery delivery) {
        this.outboxMapper = mapper;
        this.delivery = delivery;
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
            try {
                if (delivery.deliver(row.getId())) sent++;
            } catch (RuntimeException blocked) {
                // 锁冲突/数据库故障不写发送失败次数；整行事务回滚，下一轮再当前读。
                log.warn("Outbox 行未完成，保留待投递状态: id={}", row.getId(), blocked);
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

    /** 与逐行投递共用退避规则，供已有测试核验。 */
    static Duration backoff(int n) { return OutboxEventDelivery.backoff(n); }
}
