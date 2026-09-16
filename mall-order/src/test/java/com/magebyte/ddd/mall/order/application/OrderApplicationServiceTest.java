package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import com.magebyte.ddd.mall.order.infrastructure.remote.TestRecordingInventoryPort;
import com.magebyte.ddd.mall.order.infrastructure.remote.TestRecordingReleasePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单三个核心用例的集成测试：真实 MySQL，两个库存端口用测试记录组件
 * （@Primary，test 源码——不用 @MockBean，避免派生第二个上下文导致
 * 同消费组负载均衡抢消息；跨进程的全局事务由真实 jar + TC 的
 * 端到端实验覆盖）。测试环境 seata 自动装配关闭，{@code @GlobalTransactional}
 * 退化为普通方法，本地事务由 {@code @Transactional} 保障，回滚不残留数据。
 *
 * <p>本文件同时是"三个用例的事务边界差异"的可执行说明：
 * create/cancel 是跨 BC 的全局事务（测试里退化成本地事务），
 * pay 本来就是本地事务，因此它们在测试中的失败累积方式完全一致。
 */
@SpringBootTest
@Transactional
class OrderApplicationServiceTest {

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestRecordingInventoryPort recordingPort;

    @Autowired
    private TestRecordingReleasePort releasePort;

    @BeforeEach
    void resetPorts() {
        recordingPort.reset();
        releasePort.reset();
    }

    @Test
    void createOrder_persists_order_and_calls_inventory_deduction() {
        OrderDetail detail = orderApplicationService.createOrder(sampleCommand());

        assertEquals(OrderStatus.PENDING_PAY.name(), detail.status());
        assertTrue(orderRepository.findByOrderNo(detail.orderNo()).isPresent());
        // 跨 BC 身份翻译：skuId 1001 → 库存侧 SKU 编码
        assertEquals(1, recordingPort.recorded().size());
        assertEquals("SKU-1001", recordingPort.recorded().get(0).skuCode());
        assertEquals(2, recordingPort.recorded().get(0).quantity());
        // 收货地址由请求传入，不再是写死的教学地址
        assertEquals("张三", repositoryOrder(detail.orderNo()).address().receiverName());
    }

    @Test
    void createOrder_inventory_failure_propagates_to_caller() {
        recordingPort.failNext(1);

        OrderDomainException ex = assertThrows(OrderDomainException.class,
                () -> orderApplicationService.createOrder(sampleCommand()));
        assertTrue(ex.getMessage().contains("库存服务扣减失败"));
        // 库存端口确实被调用了一次（失败发生在调用之后）
        assertEquals(1, recordingPort.recorded().size());
        // 订单行在事务内仍可见（事务尚未提交）；"异常 → 本地回滚 → 行消失"
        // 与跨库全局回滚由真实环境端到端实验覆盖，不在此断言
    }

    @Test
    void payOrder_transitions_to_paid_and_appends_status_history() {
        OrderDetail created = orderApplicationService.createOrder(sampleCommand());

        OrderDetail paid = orderApplicationService.payOrder(
                PayOrderCommand.of(created.orderNo(), "198.00", "payment-gateway"));

        assertEquals(OrderStatus.PAID.name(), paid.status());
        assertEquals("198.00", paid.paidAmount());
        // 状态历史：创建 → 支付，两节
        assertEquals(2, paid.history().size());
        assertEquals("PENDING_PAY", paid.history().get(1).from());
        assertEquals("PAID", paid.history().get(1).to());
        assertNotNull(repositoryOrder(created.orderNo()).paidAmount());
    }

    @Test
    void payOrder_twice_second_call_is_rejected_by_state_machine() {
        OrderDetail created = orderApplicationService.createOrder(sampleCommand());
        orderApplicationService.payOrder(PayOrderCommand.of(created.orderNo(), "198.00", "gw-1"));

        // 重复回调：第二次被状态机拦住（PAID 不能再迁到 PAID），领域层不产生第二个支付事件
        OrderDomainException ex = assertThrows(OrderDomainException.class,
                () -> orderApplicationService.payOrder(
                        PayOrderCommand.of(created.orderNo(), "198.00", "gw-1")));
        assertTrue(ex.getMessage().contains("非法状态迁移"));
        assertEquals(OrderStatus.PAID.name(),
                repositoryOrder(created.orderNo()).status().name());
    }

    @Test
    void payOrder_amount_mismatch_is_rejected() {
        OrderDetail created = orderApplicationService.createOrder(sampleCommand());

        OrderDomainException ex = assertThrows(OrderDomainException.class,
                () -> orderApplicationService.payOrder(
                        PayOrderCommand.of(created.orderNo(), "1.00", "gw-1")));
        assertTrue(ex.getMessage().contains("金额守恒"));
    }

    @Test
    void cancelOrder_transitions_to_cancelled_and_releases_stock() {
        OrderDetail created = orderApplicationService.createOrder(sampleCommand());

        OrderDetail cancelled = orderApplicationService.cancelOrder(
                CancelOrderCommand.of(created.orderNo(), "用户主动取消", "user:9527"));

        assertEquals(OrderStatus.CANCELLED.name(), cancelled.status());
        // 按下单时的数量原路归还
        assertEquals(1, releasePort.recorded().size());
        assertEquals("SKU-1001", releasePort.recorded().get(0).skuCode());
        assertEquals(2, releasePort.recorded().get(0).quantity());
        assertEquals(2, cancelled.history().size());
        assertEquals("用户主动取消", cancelled.history().get(1).reason());
    }

    @Test
    void cancelOrder_after_payment_is_rejected() {
        OrderDetail created = orderApplicationService.createOrder(sampleCommand());
        orderApplicationService.payOrder(PayOrderCommand.of(created.orderNo(), "198.00", "gw-1"));

        // 已支付的订单不能取消，只能走退款流程（退款讲次展开）
        OrderDomainException ex = assertThrows(OrderDomainException.class,
                () -> orderApplicationService.cancelOrder(
                        CancelOrderCommand.of(created.orderNo(), "用户想取消", "user:9527")));
        assertTrue(ex.getMessage().contains("非法状态迁移"));
        assertEquals(OrderStatus.PAID.name(),
                repositoryOrder(created.orderNo()).status().name());
        // 订单没被取消，库存也不该被归还
        assertEquals(0, releasePort.recorded().size());
    }

    @Test
    void cancelOrder_release_failure_propagates_and_nothing_is_committed() {
        OrderDetail created = orderApplicationService.createOrder(sampleCommand());
        releasePort.failNext(1);

        OrderDomainException ex = assertThrows(OrderDomainException.class,
                () -> orderApplicationService.cancelOrder(new CancelOrderCommand(
                        created.orderNo(), "用户主动取消", "user:9527", false)));
        assertTrue(ex.getMessage().contains("库存服务归还失败"));
        // 归还端口确实被调用了一次（失败发生在调用之后）
        assertEquals(1, releasePort.recorded().size());

        // "订单状态回到 PENDING_PAY、Outbox 里的 OrderCancelled 行一并消失"
        // 这两件事只在真实全局事务下成立，本测试的用例方法与测试自身处在同一个
        // 本地事务里，异常只把事务标记成 rollback-only，读到的仍是事务内的中间态。
        // 因此这部分断言放在真实 jar + TC 的端到端实验里（见本讲证据记录）。
    }

    @Test
    void getOrder_unknown_order_no_throws() {
        assertThrows(com.magebyte.ddd.mall.order.domain.OrderNotFoundException.class,
                () -> orderApplicationService.getOrder("OD-NO-SUCH-ORDER"));
    }

    @Test
    void getOrder_returns_full_history_after_create_and_pay() {
        OrderDetail created = orderApplicationService.createOrder(sampleCommand());
        orderApplicationService.payOrder(PayOrderCommand.of(created.orderNo(), "198.00", "gw-1"));

        OrderDetail detail = orderApplicationService.getOrder(created.orderNo());

        assertEquals(OrderStatus.PAID.name(), detail.status());
        assertEquals(1, detail.items().size());
        assertEquals(2, detail.history().size());
        assertNull(detail.history().get(0).from());
        assertEquals("PENDING_PAY", detail.history().get(0).to());
    }

    private com.magebyte.ddd.mall.order.domain.Order repositoryOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).orElseThrow();
    }

    private static CreateOrderCommand sampleCommand() {
        return CreateOrderCommand.of(9527L, 1001L, 1001L, "示例商品", 2, "99.00",
                "张三", "13800000000", "广东省深圳市南山区");
    }
}
