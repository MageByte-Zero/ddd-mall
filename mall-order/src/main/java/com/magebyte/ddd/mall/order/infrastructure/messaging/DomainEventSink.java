package com.magebyte.ddd.mall.order.infrastructure.messaging;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 领域事件内存落点：日志消费者每收到一条事件就存进来。
 *
 * <p>教学期充当"订阅方确实收到了事件"的观察窗口——集成测试用它断言
 * 事件到达时刻与载荷内容；真实业务订阅方（库存释放、支付联动等）在
 * 后续讲次进场后，本类随之删除，消费者不再只打日志。
 */
@Component
public class DomainEventSink {

    private final List<ReceivedOrderEvent> received =
            Collections.synchronizedList(new ArrayList<>());

    /** 消费者收到事件时调用：并发队列，生产者/消费者线程不同也安全。 */
    public void offer(ReceivedOrderEvent event) {
        received.add(event);
    }

    /**
     * 等待指定 tag（事件名）的事件到达，轮询到即返回；超时返回 empty。
     * 用于断言需求文档"状态变更后 5 秒内事件到达消费者"的验收条件。
     */
    public Optional<ReceivedOrderEvent> awaitByTag(String tag, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (received) {
                for (ReceivedOrderEvent event : received) {
                    if (event.tag().equals(tag)) {
                        return Optional.of(event);
                    }
                }
            }
            Thread.sleep(100);
        }
        return Optional.empty();
    }

    /** 当前已收到的全部事件（不可变快照）。 */
    public List<ReceivedOrderEvent> all() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /** 每条测试开始前清空，避免用例间互相干扰。 */
    public void clear() {
        received.clear();
    }
}
