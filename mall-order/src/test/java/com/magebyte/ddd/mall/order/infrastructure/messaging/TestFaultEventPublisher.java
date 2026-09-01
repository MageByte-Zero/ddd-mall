package com.magebyte.ddd.mall.order.infrastructure.messaging;

import com.magebyte.ddd.mall.order.domain.event.DomainEvent;
import com.magebyte.ddd.mall.order.domain.event.DomainEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试专用发布端口：默认完全委托真实 RocketMQ 适配器；{@link #failNext(int)}
 * 之后的前 N 次发布抛异常，用于故障注入（broker 宕机、网络抖动）。
 *
 * <p>放在 test 源码、被组件扫描装配为 @Primary：所有 {@code @SpringBootTest}
 * 共用同一个上下文（不引入额外 @TestConfiguration 导致上下文分裂），
 * 也就只有一个 RocketMQ 消费实例，不会出现同消费组负载均衡抢走消息的问题。
 * 打 jar 时 test 类不参与打包，生产环境只有 {@link OrderEventPublisher} 一个实现。
 */
@Component
@Primary
public class TestFaultEventPublisher implements DomainEventPublisher {

    private final OrderEventPublisher delegate;
    private final AtomicInteger failuresRemaining = new AtomicInteger(0);

    public TestFaultEventPublisher(OrderEventPublisher delegate) {
        this.delegate = delegate;
    }

    /** 接下来的 times 次发布抛异常，之后恢复正常委托。 */
    public void failNext(int times) {
        failuresRemaining.set(times);
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new RuntimeException("注入故障：模拟 MQ 发送失败（broker 不可用/网络抖动）");
        }
        delegate.publishAll(events);
    }
}
