package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.order.domain.InventoryDeductionPort;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试专用库存端口：默认只记录调用；{@link #failNext(int)} 之后的前 N 次
 * 调用抛出领域异常，模拟库存侧业务失败（库存不足等）。
 *
 * <p>与 {@code TestFaultEventPublisher} 同样的考虑：放在 test 源码、
 * 组件扫描装配为 @Primary，所有 {@code @SpringBootTest} 共用同一个上下文，
 * 也就只有一个 RocketMQ 消费实例，不会出现同消费组负载均衡抢走消息的问题
 * （不用 @MockBean——它会派生第二个上下文）。打 jar 时 test 类不参与打包，
 * 生产环境只有 {@link FeignInventoryDeductionAdapter} 一个实现。
 */
@Component
@Primary
public class TestRecordingInventoryPort implements InventoryDeductionPort {

    public record Deduction(String skuCode, int quantity) {
    }

    private final List<Deduction> recorded = new ArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger(0);

    /** 接下来的 times 次调用抛库存失败，之后恢复正常记录。 */
    public void failNext(int times) {
        failuresRemaining.set(times);
    }

    public void reset() {
        failuresRemaining.set(0);
        synchronized (recorded) {
            recorded.clear();
        }
    }

    public List<Deduction> recorded() {
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    @Override
    public void deduct(String skuCode, int quantity) {
        // 先记录"被调用了"，再判故障：失败用例也要能断言端口被调用
        synchronized (recorded) {
            recorded.add(new Deduction(skuCode, quantity));
        }
        if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new OrderDomainException("库存服务扣减失败（HTTP 422）：注入故障，库存不足");
        }
    }
}
