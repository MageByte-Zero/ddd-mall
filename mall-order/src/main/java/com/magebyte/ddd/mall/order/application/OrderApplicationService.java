package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.InventoryDeductionPort;
import com.magebyte.ddd.mall.order.domain.InventoryReleasePort;
import com.magebyte.ddd.mall.order.domain.InventoryReservationLookup;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderNotFoundException;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderPaymentJournal;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalLock;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 订单应用服务：用例编排，不含业务规则（规则在 Order 聚合里）。
 *
 * <p>第 11 讲把订单的三个核心用例补全，恰好也把"事务边界画在哪"这件事讲透——
 * 三个用例的事务语义是<b>不一样</b>的，而差异只由一件事决定：
 * <b>这一步要不要同时动另一个 BC 的数据</b>。
 *
 * <ul>
 *   <li>{@link #createOrder}：订单 + 库存同时落 → {@code @GlobalTransactional}
 *       全局事务（AT），分支一本地订单、分支二 Feign 扣库存；</li>
 *   <li>{@link #cancelOrder}：订单状态 + 库存归还同时落 → 同样是全局事务，
 *       取消和下单是一对对称动作，"改了订单没还库存"是要赔钱的对账缺口；</li>
 *   <li>{@link #payOrder}：只动订单库 → 普通本地事务 + Outbox，
 *       顺手把 OrderPaid 事件和业务数据写在同一事务里；</li>
 *   <li>{@link #getOrder}：只读，{@code readOnly = true}。</li>
 * </ul>
 *
 * <p>把全局事务只留给真正跨 BC 的用例，是为了避免给每个用例都套上 AT 的成本：
 * 一次全局事务要多一轮 TC 通信、多一张 undo_log、多一个全局锁竞争者。
 * 支付没有第二个参与者，就不该付这份钱。
 */
@Service
public class OrderApplicationService {

    private static final Logger log = LoggerFactory.getLogger(OrderApplicationService.class);

    private final OrderRepository orderRepository;
    private final InventoryDeductionPort inventoryDeduction;
    private final InventoryReleasePort inventoryRelease;
    private final OrderPaymentJournal paymentStore;
    private final InventoryReservationLookup reservationLookup;

    /**
     * 教学故障注入的停留秒数：分支一阶段提交后故意停留，
     * 供人工/脚本在窗口期制造脏数据，再抛异常触发全局回滚（回滚失败边界实验）。
     */
    @Value("${ddd.seata.demo-failure-sleep-seconds:20}")
    private long demoFailureSleepSeconds;

    public OrderApplicationService(OrderRepository orderRepository,
                                   InventoryDeductionPort inventoryDeduction,
                                   InventoryReleasePort inventoryRelease,
                                   OrderPaymentJournal paymentStore,
                                   InventoryReservationLookup reservationLookup) {
        this.orderRepository = orderRepository;
        this.inventoryDeduction = inventoryDeduction;
        this.inventoryRelease = inventoryRelease;
        this.paymentStore = paymentStore;
        this.reservationLookup = reservationLookup;
    }

    /**
     * 创建订单：落订单 + 扣库存，要么都成功，要么都回滚。
     *
     * <p>{@code @GlobalTransactional} 默认对所有异常回滚（比 Spring
     * {@code @Transactional} 只回滚 RuntimeException 更宽），这里显式写
     * rollbackFor 让读者一眼看到语义。
     *
     * @param command 创建命令（含收货地址与教学故障开关）
     * @return 订单详情
     */
    @GlobalTransactional(name = "createOrder", rollbackFor = Exception.class)
    @Transactional
    public OrderDetail createOrder(CreateOrderCommand command) {
        log.info("创建订单用例开始，全局事务 XID={}", RootContext.getXID());

        Order order = Order.create(command.userId(), toAddress(command));
        if (command.lines().isEmpty()) {
            throw new OrderDomainException("订单至少需要一行");
        }
        for (var line : command.lines()) {
            order.addLine(line.productId(), line.skuId(), line.productName(),
                    line.quantity(), Money.of(line.unitPrice()), line.purchaseRequestNo());
        }
        // 分支一：订单库本地事务（订单、订单项、状态历史、Outbox 事件行同生共死）
        Order saved = orderRepository.save(order);
        // 分支二：Feign → 库存 BC 扣减（XID 随 TX_XID 头传播，库存侧本地事务登记为分支）
        for (var item : saved.getItems()) {
            inventoryDeduction.deduct(item.reservationNo(), item.inventorySkuCode(), item.quantity());
        }

        if (command.simulateRollbackFailure()) {
            log.warn("教学故障注入：库存分支已提交，停留 {} 秒后抛出失败，观察全局回滚",
                    demoFailureSleepSeconds);
            sleepQuietly(demoFailureSleepSeconds);
            throw new OrderDomainException("教学故障注入：库存扣减成功后故意失败，触发全局回滚");
        }
        return OrderDetail.from(saved);
    }

    /**
     * 存量教学入口：订单内登记实付金额（PENDING_PAY → PAID）。
     * 新路径在 AcceptPaymentService 消费支付确认事实后执行相同领域迁移。
     *
     * <p>只动订单自己的库，所以是普通本地事务，不需要 AT：
     * 这里没有第二个资源参与者，开全局事务纯粹是浪费一轮 TC 通信和一张 undo_log。
     * 事件不是"发出去"的，而是跟着业务数据写进同一个事务的 Outbox 行——
     * 事务提交成功，事件必然存在；事务回滚，事件连出生的机会都没有。
     *
     * <p>幂等由两层兜底：同一个请求顺序重放时，状态机直接拒绝第二次
     * （PAID 不能再迁到 PAID，抛 {@link OrderDomainException}）；
     * 订单在确认前持有当前行锁，状态判断与写入在同一事务内，仓储仍校验版本。
     *
     * @param command 支付命令
     * @return 支付后的订单详情
     */
    @GlobalLock(lockRetryInterval = 100, lockRetryTimes = 3)
    @Transactional
    public OrderDetail payOrder(PayOrderCommand command) {
        if (paymentStore.lock(command.orderNo()).managed()) {
            throw new OrderDomainException("该订单只能接受支付系统确认的收款");
        }
        Order order = findOrder(command.orderNo());
        order.markPaid(Money.of(command.paidAmount()), command.operatedBy(),
                LocalDateTime.now());
        Order saved = orderRepository.save(order);
        log.info("订单 {} 已支付，operatedBy={}", saved.orderNo(), command.operatedBy());
        return OrderDetail.from(saved);
    }

    /**
     * 取消订单：订单状态回到 CANCELLED，同时把下单时扣掉的库存还回去。
     *
     * <p>这个用例必须走全局事务，理由是经济上的而不是技术上的：
     * 如果订单改成已取消而库存没还回来，这笔库存就凭空消失了——用户没买到东西，
     * 商家也卖不出去。反过来库存还了、订单还是待支付，用户就能再下一单把同一件库存
     * 卖两次。两个 BC 的数据必须同成同败，所以要 AT。
     *
     * <p>注意编排顺序：先改订单状态（分支一），再调库存归还（分支二）。
     * 任何一步失败触发全局回滚，订单会退回 PENDING_PAY，
     * 连带着 Outbox 里的 OrderCancelled 行一起消失——
     * 下游不会收到一个"发出去又反悔了"的幽灵取消事件。
     *
     * @param command 取消命令（含取消原因、操作人、教学故障开关）
     * @return 取消后的订单详情
     */
    @GlobalTransactional(name = "cancelOrder", rollbackFor = Exception.class)
    @Transactional
    public OrderDetail cancelOrder(CancelOrderCommand command) {
        log.info("取消订单用例开始，全局事务 XID={}", RootContext.getXID());
        Order order = findOrder(command.orderNo());
        order.cancel(command.reason(), command.operatedBy(), LocalDateTime.now());
        // 分支一：订单状态 + 状态历史 + Outbox 的 OrderCancelled 行，同属一个本地事务
        Order saved = orderRepository.save(order);
        // 原预占和原数量来自已保存的订单行。
        for (var item : saved.getItems()) {
            inventoryRelease.release(item.reservationNoFor(saved.orderNo()), item.inventorySkuCode(), item.quantity());
        }

        if (command.simulateReleaseFailure()) {
            log.warn("教学故障注入：库存归还分支已提交，停留 {} 秒后抛出失败，观察取消用例的全局回滚",
                    demoFailureSleepSeconds);
            sleepQuietly(demoFailureSleepSeconds);
            throw new OrderDomainException("教学故障注入：库存归还成功后故意失败，触发全局回滚");
        }
        return OrderDetail.from(saved);
    }

    /**
     * 查询订单详情（只读用例）。
     *
     * @param orderNo 订单号
     * @throws OrderNotFoundException 订单不存在时抛出（接口层翻译为 404）
     */
    @Transactional(readOnly = true)
    public OrderDetail getOrder(String orderNo) {
        return OrderDetail.from(findOrder(orderNo));
    }

    /** 发货只改订单本地状态与事实快照；库存由事件订阅最终推进。 */
    @Transactional
    public OrderDetail shipOrder(ShipOrderCommand command) {
        Order order = findOrder(command.orderNo());
        order.markShipped(command.operatedBy(), LocalDateTime.now());
        for (var item : order.getItems()) {
            if (item.usesLegacyReservation()) {
                reservationLookup.requireReserved(item.reservationNoFor(order.orderNo()),
                        item.inventorySkuCode(), item.quantity());
            }
        }
        return OrderDetail.from(orderRepository.save(order));
    }

    private Order findOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在: " + orderNo));
    }

    private static Address toAddress(CreateOrderCommand command) {
        return new Address(command.receiverName(), command.receiverPhone(),
                command.receiverAddress());
    }

    private void sleepQuietly(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
