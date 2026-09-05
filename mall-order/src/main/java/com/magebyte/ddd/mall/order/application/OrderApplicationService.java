package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.InventoryDeductionPort;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单应用服务：用例编排，不含业务规则（规则在 Order 聚合里）。
 *
 * <p>{@link #createOrder} 是 Seata AT 全局事务的起点（TM 角色）：
 * {@code @GlobalTransactional} 开启全局事务，订单本地写入是第一个分支，
 * Feign 调用库存 BC 的扣减是第二个分支；库存侧抛异常回到这里，
 * 全局回滚让两个库的数据回到事务前。
 */
@Service
public class OrderApplicationService {

    private static final Logger log = LoggerFactory.getLogger(OrderApplicationService.class);

    private final OrderRepository orderRepository;
    private final InventoryDeductionPort inventoryDeduction;

    /**
     * 教学故障注入的停留秒数：库存分支一阶段提交后故意停留，
     * 供人工/脚本在窗口期制造脏数据，再抛异常触发全局回滚（回滚失败边界实验）。
     */
    @Value("${ddd.seata.demo-failure-sleep-seconds:20}")
    private long demoFailureSleepSeconds;

    public OrderApplicationService(OrderRepository orderRepository,
                                   InventoryDeductionPort inventoryDeduction) {
        this.orderRepository = orderRepository;
        this.inventoryDeduction = inventoryDeduction;
    }

    /**
     * 创建订单：落订单 + 扣库存，要么都成功，要么都回滚。
     *
     * <p>{@code @GlobalTransactional} 默认对所有异常回滚（比 Spring
     * {@code @Transactional} 只回滚 RuntimeException 更宽），这里显式写
     * rollbackFor 让读者一眼看到语义。
     *
     * @param simulateRollbackFailure 教学开关：true 时在扣库存成功后停留再失败，
     *                                用于演示全局回滚（含回滚失败边界），业务代码无此分支
     * @return 订单号
     */
    @GlobalTransactional(name = "createOrder", rollbackFor = Exception.class)
    @Transactional
    public String createOrder(Long userId, Long productId, Long skuId, String productName,
                              int quantity, String unitPrice, boolean simulateRollbackFailure) {
        log.info("创建订单用例开始，全局事务 XID={}", RootContext.getXID());

        Order order = Order.create(userId,
                new Address("教学用户", "13800000000", "广东省深圳市南山区"));
        order.addItem(OrderItem.create(productId, skuId, productName, quantity,
                Money.of(unitPrice)));
        // 分支一：订单库本地事务（订单、订单项、状态历史、Outbox 事件行同生共死）
        orderRepository.save(order);
        // 分支二：Feign → 库存 BC 扣减（XID 随 TX_XID 头传播，库存侧本地事务登记为分支）
        inventoryDeduction.deduct(toInventorySkuCode(skuId), quantity);

        if (simulateRollbackFailure) {
            log.warn("教学故障注入：库存分支已提交，停留 {} 秒后抛出失败，观察全局回滚",
                    demoFailureSleepSeconds);
            sleepQuietly(demoFailureSleepSeconds);
            throw new OrderDomainException("教学故障注入：库存扣减成功后故意失败，触发全局回滚");
        }
        return order.orderNo();
    }

    /**
     * 跨 BC 身份翻译的临时占位：订单侧 SKU 用数字 id，库存侧用 SKU 编码。
     * 正式翻译由商品 BC / 跨 BC 契约提供（防腐层讲次落地），本讲先用确定映射。
     */
    private String toInventorySkuCode(Long skuId) {
        return "SKU-" + skuId;
    }

    private void sleepQuietly(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
