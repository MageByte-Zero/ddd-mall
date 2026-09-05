package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import com.magebyte.ddd.mall.order.infrastructure.remote.TestRecordingInventoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单应用服务用例集成测试：真实 MySQL，库存端口用测试记录组件
 * （@Primary，test 源码——不用 @MockBean，避免派生第二个上下文导致
 * 同消费组负载均衡抢消息；跨进程的全局事务由真实 jar + TC 的
 * 端到端实验覆盖）。测试环境 seata 自动装配关闭，{@code @GlobalTransactional}
 * 退化为普通方法，本地事务由 {@code @Transactional} 保障，回滚不残留数据。
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

    @BeforeEach
    void resetPort() {
        recordingPort.reset();
    }

    @Test
    void createOrder_persists_order_and_calls_inventory_deduction() {
        String orderNo = orderApplicationService.createOrder(
                9527L, 1001L, 1001L, "示例商品", 2, "99.00", false);

        assertTrue(orderRepository.findByOrderNo(orderNo).isPresent());
        assertEquals(OrderStatus.PENDING_PAY,
                orderRepository.findByOrderNo(orderNo).get().status());
        // 跨 BC 身份翻译：skuId 1001 → 库存侧 SKU 编码
        assertEquals(1, recordingPort.recorded().size());
        assertEquals("SKU-1001", recordingPort.recorded().get(0).skuCode());
        assertEquals(2, recordingPort.recorded().get(0).quantity());
    }

    @Test
    void createOrder_inventory_failure_propagates_to_caller() {
        recordingPort.failNext(1);

        OrderDomainException ex = assertThrows(OrderDomainException.class,
                () -> orderApplicationService.createOrder(
                        9527L, 1001L, 1001L, "示例商品", 5, "99.00", false));
        assertTrue(ex.getMessage().contains("库存服务扣减失败"));
        // 库存端口确实被调用了一次（失败发生在调用之后）
        assertEquals(1, recordingPort.recorded().size());
        // 订单行在事务内仍可见（事务尚未提交）；"异常 → 本地回滚 → 行消失"
        // 与跨库全局回滚由真实环境端到端实验覆盖，不在此断言
    }
}
