package com.magebyte.ddd.mall.order.domain;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单（Order）聚合根。
 *
 * <p>订单聚合 = 订单根实体 + 订单项实体 + Money/Address 值对象。
 * 聚合是数据修改与持久化的基本单元：外部只能通过本类的方法修改聚合内部，
 * 一致性由本类守护，一个聚合对应一个仓储（{@link OrderRepository}）。
 *
 * <p>本讲守护的不变量（订单需求文档第 4 节第 1 条"金额守恒"及其配套规则）：
 * <ul>
 *   <li>金额守恒：{@code totalAmount} 永远等于 Σ 订单项小计——
 *       它不做独立赋值，只在增项时由订单项推导，重组时做反向校验；</li>
 *   <li>订单至少有一个订单项才算可支付（空单不能进入支付）；</li>
 *   <li>状态变更必须经过 {@link OrderStatus#canTransitionTo} 的合法迁移表；</li>
 *   <li>实付金额必须等于应付金额——需求里没有部分支付，领域层不留口子；</li>
 *   <li>进入 PAID / CANCELLED 后订单项冻结，不允许再增改。</li>
 * </ul>
 */
public class Order {

    private static final DateTimeFormatter ORDER_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Long id;
    private String orderNo;
    private Long userId;
    private OrderStatus status;
    private final List<OrderItem> items = new ArrayList<>();
    private Money totalAmount;
    private Money paidAmount;
    private Address address;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Order() {
    }

    /**
     * 创建订单：进入 PENDING_PAY，总金额归零等待增项。
     * 订单号在领域内生成（教学取最简实现，生产级发号方案见后续讲次）。
     */
    public static Order create(Long userId, Address address) {
        Objects.requireNonNull(userId, "下单用户ID不能为空");
        Objects.requireNonNull(address, "收货地址不能为空");
        Order order = new Order();
        order.orderNo = generateOrderNo();
        order.userId = userId;
        order.status = OrderStatus.PENDING_PAY;
        order.totalAmount = Money.zero();
        order.address = address;
        order.createdAt = LocalDateTime.now();
        order.updatedAt = order.createdAt;
        return order;
    }

    /**
     * 从持久化数据重组聚合，仓储实现专用入口。
     * 重组即校验：落库数据若已破坏金额守恒，宁可抛出也不带回病态对象。
     */
    public static Order reconstitute(Long id, String orderNo, Long userId, OrderStatus status,
                                     List<OrderItem> items, Money totalAmount, Money paidAmount,
                                     Address address, Integer version,
                                     LocalDateTime createdAt, LocalDateTime updatedAt) {
        Objects.requireNonNull(id, "持久化订单的 id 不能为空");
        Objects.requireNonNull(orderNo, "持久化订单的订单号不能为空");
        Objects.requireNonNull(userId, "持久化订单的用户ID不能为空");
        Objects.requireNonNull(status, "持久化订单的状态不能为空");
        Objects.requireNonNull(items, "持久化订单的订单项不能为空");
        Objects.requireNonNull(totalAmount, "持久化订单的总金额不能为空");
        Objects.requireNonNull(version, "持久化订单的版本号不能为空");

        Money recalculated = items.stream()
                .map(OrderItem::subtotal)
                .reduce(Money.zero(), Money::plus);
        if (!recalculated.equals(totalAmount)) {
            throw new OrderDomainException("持久化数据违反金额守恒不变量：订单总金额 "
                    + totalAmount + " 与订单项小计之和 " + recalculated + " 不一致");
        }

        Order order = new Order();
        order.id = id;
        order.orderNo = orderNo;
        order.userId = userId;
        order.status = status;
        order.items.addAll(items);
        order.totalAmount = totalAmount;
        order.paidAmount = paidAmount;
        order.address = Objects.requireNonNull(address, "持久化订单的收货地址不能为空");
        order.version = version;
        order.createdAt = createdAt;
        order.updatedAt = updatedAt;
        return order;
    }

    /**
     * 增加订单项。仅待支付状态可增项；总金额随之重算，金额守恒由构造保证。
     */
    public void addItem(OrderItem item) {
        Objects.requireNonNull(item, "订单项不能为空");
        assertItemsModifiable();
        items.add(item);
        totalAmount = totalAmount.plus(item.subtotal());
    }

    /**
     * 支付成功：PENDING_PAY → PAID。
     * 实付必须等于应付（金额守恒），状态迁移必须合法。
     */
    public void markPaid(Money paidAmount, LocalDateTime when) {
        Objects.requireNonNull(paidAmount, "实付金额不能为空");
        Objects.requireNonNull(when, "支付时间不能为空");
        assertTransition(OrderStatus.PAID);
        if (items.isEmpty()) {
            throw new OrderDomainException("空订单不能支付：至少需要一个订单项");
        }
        if (!paidAmount.equals(totalAmount)) {
            throw new OrderDomainException("金额守恒：实付 " + paidAmount
                    + " 与应付 " + totalAmount + " 不一致");
        }
        this.status = OrderStatus.PAID;
        this.paidAmount = paidAmount;
        this.updatedAt = when;
    }

    /** 取消订单：仅 PENDING_PAY → CANCELLED。 */
    public void cancel(LocalDateTime when) {
        Objects.requireNonNull(when, "取消时间不能为空");
        assertTransition(OrderStatus.CANCELLED);
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = when;
    }

    /** 对外只暴露不可变快照：聚合内部列表不逃逸。 */
    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    public Long id() {
        return id;
    }

    public String orderNo() {
        return orderNo;
    }

    public Long userId() {
        return userId;
    }

    public OrderStatus status() {
        return status;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public Money paidAmount() {
        return paidAmount;
    }

    public Address address() {
        return address;
    }

    public Integer version() {
        return version;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    private void assertItemsModifiable() {
        if (status != OrderStatus.PENDING_PAY) {
            throw new OrderDomainException("订单已处于 " + status + " 状态，不允许再修改订单项");
        }
    }

    private void assertTransition(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new OrderDomainException(
                    "非法状态迁移: " + status + " -> " + target);
        }
    }

    private static String generateOrderNo() {
        String timestamp = LocalDateTime.now().format(ORDER_NO_FORMAT);
        int suffix = ThreadLocalRandom.current().nextInt(1_000_000);
        return "OD" + timestamp + String.format("%06d", suffix);
    }
}
