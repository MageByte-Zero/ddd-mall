package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.order.domain.InventoryReleasePort;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试专用库存归还端口：默认只记录调用；{@link #failNext(int)} 之后的前 N 次
 * 调用抛出领域异常，模拟库存侧业务失败（重复释放、越界归还等）。
 *
 * <p>与 {@link TestRecordingInventoryPort} 完全同构，只是实现了另一个端口：
 * 两个端口各自有一个 @Primary 实现，Spring 能按接口类型区分，不会冲突。
 */
@Component
@Primary
public class TestRecordingReleasePort implements InventoryReleasePort {

    public record Release(String skuCode, int quantity) {
    }

    private final List<Release> recorded = new ArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger(0);

    /** 接下来的 times 次调用抛归还失败，之后恢复正常记录。 */
    public void failNext(int times) {
        failuresRemaining.set(times);
    }

    public void reset() {
        failuresRemaining.set(0);
        synchronized (recorded) {
            recorded.clear();
        }
    }

    public List<Release> recorded() {
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    @Override
    public void release(String skuCode, int quantity) {
        synchronized (recorded) {
            recorded.add(new Release(skuCode, quantity));
        }
        if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new OrderDomainException("库存服务归还失败（HTTP 422）：注入故障，归还越界");
        }
    }
}
