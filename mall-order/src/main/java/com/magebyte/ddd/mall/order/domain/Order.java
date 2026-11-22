package com.magebyte.ddd.mall.order.domain;

import com.magebyte.ddd.mall.order.domain.event.DomainEvent;
import com.magebyte.ddd.mall.order.domain.event.RefundRequestedEvent;
import com.magebyte.ddd.mall.order.domain.event.RefundCompletedEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderCancelledEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderCreatedEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderPaidEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderReceivedEvent;
import com.magebyte.ddd.mall.order.domain.event.OrderShippedEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单（Order）聚合根。
 *
 * <p>订单聚合包含订单行、退款申请以及 Money/Address 等值对象。
 * 聚合是数据修改与持久化的基本单元：外部只能通过本类的方法修改聚合内部，
 * 一致性由本类守护，一个聚合对应一个仓储（{@link OrderRepository}）。
 *
 * <p>守护的不变量（订单需求文档第 4 节"金额守恒""状态机守恒"及其配套规则）：
 * <ul>
 *   <li>金额守恒：{@code totalAmount} 永远等于 Σ 订单项小计——
 *       它不做独立赋值，只在增项时由订单项推导，重组时做反向校验；</li>
 *   <li>订单至少有一个订单项才算可支付（空单不能进入支付）；</li>
 *   <li>状态机守恒：状态变更必须经过 {@link OrderStatus#canTransitionTo} 的合法迁移表，
 *       且每次迁移都向状态历史链追加一节 {@link StatusChange}——历史只增不改，
 *       节与节首尾相接，末节的 to 永远等于当前状态；</li>
 *   <li>实付金额必须等于应付金额——需求里没有部分支付，领域层不留口子；</li>
 *   <li>进入 PAID / CANCELLED 后订单项冻结，不允许再增改；</li>
 *   <li>已退和在途的部分退款按行不超过购买量，按订单不超过实付；</li>
 *   <li>事件可达：订单创建和每次合法状态迁移都抛出对应的领域事件
 *       （OrderCreated / OrderPaid / OrderShipped / OrderReceived /
 *       OrderCancelled / RefundRequested / RefundCompleted），事件先收集在聚合内部，
 *       仓储与业务数据共同写入Outbox，提交后由中继投递；
 *       重组历史不产生事件——事件只代表"新发生的事实"。</li>
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
    private final List<StatusChange> statusHistory = new ArrayList<>();
    /**
     * 本聚合自上次保存以来产生的领域事件。只增不直接暴露：
     * 外部通过 {@link #pullEvents()} 取走快照并清空，发布是基础设施层的事。
     */
    private final List<DomainEvent> events = new ArrayList<>();
    private Money totalAmount;
    private Money paidAmount;
    private Refund refund;
    private final Map<String, PartialRefund> partialRefunds = new LinkedHashMap<>();
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
        // 历史链第一节：从"无状态"进入待支付，from 留空
        order.statusHistory.add(new StatusChange(null, OrderStatus.PENDING_PAY,
                "用户下单", "user:" + userId, order.createdAt));
        // 订单出生是第一个业务事实：抛出 OrderCreated 事件
        order.events.add(OrderCreatedEvent.raise(order.orderNo, userId, order.createdAt));
        return order;
    }

    /**
     * 从持久化数据重组聚合，仓储实现专用入口。
     * 重组即校验：落库数据若已破坏金额守恒，宁可抛出也不带回病态对象。
     * 注意：重组是"还原历史"而不是"新发生事实"，不抛出任何领域事件——
     * 事件在当初状态变更时已经发过，读回来再发一遍会造成重复通知。
     */
    public static Order reconstitute(Long id, String orderNo, Long userId, OrderStatus status,
                                     List<OrderItem> items, List<StatusChange> statusHistory,
                                     Money totalAmount, Money paidAmount,
                                     Address address, Integer version,
                                     LocalDateTime createdAt, LocalDateTime updatedAt) {
        return reconstitute(id, orderNo, userId, status, items, statusHistory, totalAmount,
                paidAmount, address, version, createdAt, updatedAt, null);
    }

    public static Order reconstitute(Long id, String orderNo, Long userId, OrderStatus status,
                                     List<OrderItem> items, List<StatusChange> statusHistory,
                                     Money totalAmount, Money paidAmount, Address address, Integer version,
                                     LocalDateTime createdAt, LocalDateTime updatedAt, Refund refund) {
        return reconstitute(id, orderNo, userId, status, items, statusHistory, totalAmount,
                paidAmount, address, version, createdAt, updatedAt, refund, List.of());
    }

    public static Order reconstitute(Long id, String orderNo, Long userId, OrderStatus status,
                                     List<OrderItem> items, List<StatusChange> statusHistory,
                                     Money totalAmount, Money paidAmount, Address address, Integer version,
                                     LocalDateTime createdAt, LocalDateTime updatedAt, Refund refund,
                                     List<PartialRefund> partialRefunds) {
        Objects.requireNonNull(id, "持久化订单的 id 不能为空");
        Objects.requireNonNull(orderNo, "持久化订单的订单号不能为空");
        Objects.requireNonNull(userId, "持久化订单的用户ID不能为空");
        Objects.requireNonNull(status, "持久化订单的状态不能为空");
        Objects.requireNonNull(items, "持久化订单的订单项不能为空");
        Objects.requireNonNull(statusHistory, "持久化订单的状态历史不能为空");
        Objects.requireNonNull(totalAmount, "持久化订单的总金额不能为空");
        Objects.requireNonNull(version, "持久化订单的版本号不能为空");

        Money recalculated = items.stream()
                .map(OrderItem::subtotal)
                .reduce(Money.zero(), Money::plus);
        if (!recalculated.equals(totalAmount)) {
            throw new OrderDomainException("持久化数据违反金额守恒不变量：订单总金额 "
                    + totalAmount + " 与订单项小计之和 " + recalculated + " 不一致");
        }
        assertHistoryChain(status, statusHistory);
        boolean refundState = status == OrderStatus.REFUND_REQUESTED || status == OrderStatus.REFUNDED;
        if (refundState != (refund != null))
            throw new OrderDomainException("退款状态与申请身份不一致；历史退款须先对账迁移");
        if (refund != null) {
            if (!refund.amount().equals(paidAmount))
                throw new OrderDomainException("退款金额与实付不一致");
            StatusChange request = statusHistory.stream().filter(c -> c.to() == OrderStatus.REFUND_REQUESTED)
                    .findFirst().orElseThrow(() -> new OrderDomainException("缺少退款申请历史"));
            if (request.from() != refund.requestedFrom() || !request.reason().equals(refund.reason()))
                throw new OrderDomainException("退款申请与履约历史不一致");
            boolean succeeded = refund.result() != null && refund.result().outcome() == RefundResult.Outcome.SUCCEEDED;
            if (succeeded != (status == OrderStatus.REFUNDED))
                throw new OrderDomainException("退款结果与完成状态不一致");
        }

        Order order = new Order();
        order.id = id;
        order.orderNo = orderNo;
        order.userId = userId;
        order.status = status;
        if (items.stream().map(OrderItem::lineId).distinct().count() != items.size()) {
            throw new OrderDomainException("订单行身份重复");
        }
        for (OrderItem item : items) {
            order.requireLineOwnership(item);
        }
        order.items.addAll(items);
        order.statusHistory.addAll(statusHistory);
        order.totalAmount = totalAmount;
        order.paidAmount = paidAmount;
        order.refund = refund;
        order.address = Objects.requireNonNull(address, "持久化订单的收货地址不能为空");
        order.version = version;
        order.createdAt = createdAt;
        order.updatedAt = updatedAt;
        order.restorePartialRefunds(partialRefunds);
        return order;
    }

    private void restorePartialRefunds(List<PartialRefund> persisted) {
        if (persisted.isEmpty()) {
            return;
        }
        if (refund != null || paidAmount == null || !List.of(OrderStatus.PAID, OrderStatus.SHIPPED,
                OrderStatus.RECEIVED).contains(status)) {
            throw new OrderDomainException("部分退款记录与订单状态或退款模式冲突");
        }
        Map<String, Long> usedByLine = new HashMap<>();
        Money usedAmount = Money.zero();
        for (PartialRefund entry : persisted) {
            OrderItem line = requireRefundLine(entry.lineId());
            if (entry.quantity() > line.quantity() || !line.unitPrice().multiply(entry.quantity()).equals(entry.amount())
                    || (entry.requestedFrom() == OrderStatus.SHIPPED && status == OrderStatus.PAID)
                    || partialRefunds.putIfAbsent(entry.refundNo(), entry) != null) {
                throw new OrderDomainException("持久化的部分退款与成交行、履约状态或申请身份不一致");
            }
            if (entry.countsAgainstLimit()) {
                long usedQuantity = usedByLine.merge(line.lineId(), (long) entry.quantity(), Long::sum);
                if (usedQuantity > line.quantity()) {
                    throw new OrderDomainException("部分退款超过订单行数量");
                }
                usedAmount = usedAmount.plus(entry.amount());
            }
        }
        if (usedAmount.isGreaterThan(paidAmount)) {
            throw new OrderDomainException("部分退款超过订单实付金额");
        }
    }

    /**
     * 重组时校验状态历史链：第一节必须是创建记录（from 为空），
     * 之后每节的 from 接上一节的 to，每一步都要在合法迁移表里，
     * 且末节的 to 必须等于订单当前状态。
     */
    private static void assertHistoryChain(OrderStatus currentStatus, List<StatusChange> history) {
        if (history.isEmpty()) {
            throw new OrderDomainException("持久化数据违反状态机守恒不变量：状态历史为空");
        }
        StatusChange first = history.get(0);
        if (first.from() != null) {
            throw new OrderDomainException("状态历史第一节必须是创建记录（from 为空），实际 from="
                    + first.from());
        }
        for (int i = 0; i < history.size(); i++) {
            StatusChange change = history.get(i);
            if (i > 0 && !history.get(i - 1).to().equals(change.from())) {
                throw new OrderDomainException("状态历史断链：第 " + (i + 1) + " 节 from="
                        + change.from() + " 接不上上一节 to=" + history.get(i - 1).to());
            }
            if (change.from() != null && !change.from().canTransitionTo(change.to())) {
                throw new OrderDomainException("持久化数据违反状态机守恒不变量：非法迁移 "
                        + change.from() + " -> " + change.to());
            }
        }
        StatusChange last = history.get(history.size() - 1);
        if (!last.to().equals(currentStatus)) {
            throw new OrderDomainException("状态历史末节 to=" + last.to()
                    + " 与订单当前状态 " + currentStatus + " 不一致");
        }
    }

    /**
     * 增加订单项。仅待支付状态可增项；总金额随之重算，金额守恒由构造保证。
     */
    public void addItem(OrderItem item) {
        Objects.requireNonNull(item, "订单项不能为空");
        assertItemsModifiable();
        requireLineOwnership(item);
        if (items.contains(item)) {
            throw new OrderDomainException("订单行身份重复");
        }
        Money nextTotal = totalAmount.plus(item.subtotal());
        if (nextTotal.amount().precision() > 18) {
            throw new OrderDomainException("订单金额超出允许范围");
        }
        items.add(item);
        totalAmount = nextTotal;
    }

    public void addLine(Long productId, Long skuId, String productName,
                        int quantity, Money unitPrice, String purchaseRequestNo) {
        addItem(OrderItem.place(orderNo, productId, skuId, productName, quantity, unitPrice, purchaseRequestNo));
    }

    private void requireLineOwnership(OrderItem item) {
        if (!item.usesLegacyReservation()
                && !item.reservationNo().equals(orderNo + ":LINE:" + item.lineId())) {
            throw new OrderDomainException("订单行预占引用不属于本订单");
        }
    }

    /**
     * 支付成功：PENDING_PAY → PAID。
     * 实付必须等于应付（金额守恒），状态迁移必须合法，迁移落历史链。
     */
    public void markPaid(Money paidAmount, String operatedBy, LocalDateTime when) {
        Objects.requireNonNull(paidAmount, "实付金额不能为空");
        Objects.requireNonNull(operatedBy, "支付操作人不能为空");
        Objects.requireNonNull(when, "支付时间不能为空");
        // 所有校验先于任何状态修改：非法迁移在实付金额落字段之前就被拒绝
        assertTransition(OrderStatus.PAID);
        if (items.isEmpty()) {
            throw new OrderDomainException("空订单不能支付：至少需要一个订单项");
        }
        if (!paidAmount.equals(totalAmount)) {
            throw new OrderDomainException("金额守恒：实付 " + paidAmount
                    + " 与应付 " + totalAmount + " 不一致");
        }
        // 先落实付金额再迁移：OrderPaid 事件载荷从聚合状态取实付金额
        this.paidAmount = paidAmount;
        recordChange(OrderStatus.PAID, "支付回调成功", operatedBy, when);
    }

    /**
     * 商家发货：PAID → SHIPPED。
     * 没付钱不能发货——待支付订单调用本方法会被迁移表直接拒绝。
     */
    public void markShipped(String operatedBy, LocalDateTime when) {
        Objects.requireNonNull(operatedBy, "发货操作人不能为空");
        Objects.requireNonNull(when, "发货时间不能为空");
        assertTransition(OrderStatus.SHIPPED);
        requireFulfillmentReferences();
        recordChange(OrderStatus.SHIPPED, "商家发货", operatedBy, when);
    }

    /**
     * 用户确认收货：SHIPPED → RECEIVED（终态）。
     * 没发货不能收货——跳级迁移是非法迁移。
     */
    public void confirmReceived(String operatedBy, LocalDateTime when) {
        Objects.requireNonNull(operatedBy, "收货操作人不能为空");
        Objects.requireNonNull(when, "收货时间不能为空");
        recordChange(OrderStatus.RECEIVED, "用户确认收货", operatedBy, when);
    }

    /**
     * 取消订单：仅 PENDING_PAY → CANCELLED。
     * 触发方有两类：用户主动取消、超时未支付由定时任务取消，原因落历史链。
     * 已支付订单不能直接取消，只能走退款流程（第 15 讲）。
     */
    public void cancel(String reason, String operatedBy, LocalDateTime when) {
        Objects.requireNonNull(reason, "取消原因不能为空");
        Objects.requireNonNull(operatedBy, "取消操作人不能为空");
        Objects.requireNonNull(when, "取消时间不能为空");
        assertTransition(OrderStatus.CANCELLED);
        requireFulfillmentReferences();
        recordChange(OrderStatus.CANCELLED, reason, operatedBy, when);
    }

    private void requireFulfillmentReferences() {
        long legacy = items.stream().filter(OrderItem::usesLegacyReservation).count();
        if ((legacy != 0 && legacy != items.size())
                || items.stream().map(item -> item.reservationNoFor(orderNo)).distinct().count() != items.size()) {
            throw new OrderDomainException("订单行的历史预占归属不唯一，须先核查");
        }
    }

    /** 同一有效申请只产生一次事实；申请资格和整单金额一起由订单守护。 */
    public void requestFullRefund(String refundNo, Money amount, String reason,
                                  String operatedBy, LocalDateTime when) {
        Objects.requireNonNull(operatedBy, "退款操作人不能为空");
        Objects.requireNonNull(when, "退款申请时间不能为空");
        if (refund != null) {
            if (refund.sameRequest(refundNo, amount, reason)) return;
            throw new OrderDomainException("已有整单退款申请，身份或参数冲突");
        }
        if (!partialRefunds.isEmpty()) {
            throw new OrderDomainException("订单已使用部分退款，不能切换为整单退款");
        }
        assertTransition(OrderStatus.REFUND_REQUESTED);
        if (!Objects.equals(paidAmount, amount))
            throw new OrderDomainException("整单退款金额必须等于实付");
        Refund requested = Refund.request(refundNo, amount, reason, status);
        refund = requested;
        recordChange(OrderStatus.REFUND_REQUESTED, reason, operatedBy, when);
    }

    /** 只有可信边界提供的确定成功结果才完成；未知或拒绝仅保存观察结果。 */
    public void recordRefundResult(RefundResult result, String operatedBy, LocalDateTime when) {
        Objects.requireNonNull(operatedBy, "退款操作人不能为空");
        Objects.requireNonNull(when, "退款结果时间不能为空");
        if (refund == null) throw new OrderDomainException("尚无退款申请");
        Refund updated = refund.withResult(result);
        if (status == OrderStatus.REFUNDED) return; // withResult 已核验相同成功凭证
        assertTransition(OrderStatus.REFUNDED);
        refund = updated;
        updatedAt = when;
        if (result.outcome() == RefundResult.Outcome.SUCCEEDED)
            recordChange(OrderStatus.REFUNDED, "退款成功凭证:" + result.reference(), operatedBy, when);
    }

    /** 不可变的实体快照，调用方不能越过聚合修改退款。 */
    public Refund refund() { return refund; }

    /** 返回是否发生新写入。同号同参在任何后续履约或退款状态均可重放。 */
    public boolean requestPartialRefund(String refundNo, String lineId, int quantity, String reason, LocalDateTime when) {
        Objects.requireNonNull(when, "退款申请时间不能为空");
        PartialRefund previous = partialRefunds.get(refundNo);
        if (previous != null) {
            if (!previous.sameRequest(lineId, quantity, reason)) {
                throw new OrderDomainException("同一部分退款申请号的参数冲突");
            }
            return false;
        }
        if (refund != null) {
            throw new OrderDomainException("订单已使用整单退款，不能切换为部分退款");
        }
        if ((status != OrderStatus.PAID && status != OrderStatus.SHIPPED) || paidAmount == null) {
            throw new OrderDomainException("部分退款须在已付款或已发货时申请");
        }
        OrderItem line = requireRefundLine(lineId);
        if (quantity <= 0) {
            throw new OrderDomainException("部分退款数量须为正整数");
        }
        Money amount = line.unitPrice().multiply(quantity);
        long usedQuantity = 0;
        Money usedAmount = Money.zero();
        for (PartialRefund entry : partialRefunds.values()) {
            if (entry.countsAgainstLimit()) {
                if (entry.lineId().equals(lineId)) {
                    usedQuantity += entry.quantity();
                }
                usedAmount = usedAmount.plus(entry.amount());
            }
        }
        if (usedQuantity + quantity > line.quantity()) {
            throw new OrderDomainException("部分退款超过订单行数量");
        }
        if (usedAmount.plus(amount).isGreaterThan(paidAmount)) {
            throw new OrderDomainException("部分退款超过订单实付金额");
        }
        partialRefunds.put(refundNo, new PartialRefund(refundNo, lineId, quantity, amount, reason, status, null));
        updatedAt = when;
        return true;
    }

    public boolean recordPartialRefundResult(RefundResult result, LocalDateTime when) {
        Objects.requireNonNull(result, "退款结果不能为空");
        Objects.requireNonNull(when, "退款结果时间不能为空");
        PartialRefund current = partialRefund(result.refundNo());
        PartialRefund updated = current.withResult(result);
        if (updated == current) {
            return false;
        }
        partialRefunds.put(updated.refundNo(), updated);
        updatedAt = when;
        return true;
    }

    public PartialRefund partialRefund(String refundNo) {
        PartialRefund entry = partialRefunds.get(refundNo);
        if (entry == null) {
            throw new OrderDomainException("本订单不存在该部分退款申请");
        }
        return entry;
    }

    public List<PartialRefund> partialRefunds() {
        return List.copyOf(partialRefunds.values());
    }

    private OrderItem requireRefundLine(String lineId) {
        return items.stream().filter(line -> line.lineId().equals(lineId)).findFirst()
                .orElseThrow(() -> new OrderDomainException("本订单不存在该退款行"));
    }

    /** 对外只暴露不可变快照：聚合内部列表不逃逸。 */
    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    /** 状态历史同样只暴露不可变快照：外部只能经迁移方法追加，不能改写。 */
    public List<StatusChange> statusHistory() {
        return List.copyOf(statusHistory);
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

    /**
     * 所有状态迁移的唯一入口：先过合法迁移表，再追加历史链，然后换状态，
     * 最后抛出对应的领域事件。历史只增不改——状态机每走一步都留下一节
     * 可追溯的记录；事件只在迁移合法后才产生，非法迁移一个事件都不会抛。
     */
    private void recordChange(OrderStatus target, String reason, String operatedBy,
                              LocalDateTime when) {
        assertTransition(target);
        statusHistory.add(new StatusChange(status, target, reason, operatedBy, when));
        status = target;
        updatedAt = when;
        raiseEventFor(target, reason, operatedBy, when);
    }

    /**
     * 迁移目标状态 → 领域事件的映射。事件名全部取自第 1 讲锁定的词汇表，
     * 第15讲补退款申请与完成事实；不存在的目标状态显式报错。
     */
    private void raiseEventFor(OrderStatus target, String reason, String operatedBy,
                               LocalDateTime when) {
        switch (target) {
            case PAID -> events.add(OrderPaidEvent.raise(orderNo, paidAmount, when));
            case SHIPPED -> events.add(OrderShippedEvent.raise(orderNo, operatedBy, when, items.stream()
                    .map(item -> new OrderShippedEvent.ShipmentLine(item.reservationNoFor(orderNo),
                            item.inventorySkuCode(), item.quantity(),
                            item.usesLegacyReservation() ? null : item.lineId())).toList()));
            case RECEIVED -> events.add(OrderReceivedEvent.raise(orderNo, operatedBy, when));
            case REFUND_REQUESTED -> events.add(RefundRequestedEvent.raise(orderNo, refund, when));
            case REFUNDED -> events.add(RefundCompletedEvent.raise(orderNo, refund, when));
            case CANCELLED ->
                    events.add(OrderCancelledEvent.raise(orderNo, reason, operatedBy, when));
            default -> throw new OrderDomainException(
                    "状态 " + target + " 尚未接入领域事件发布");
        }
    }

    /**
     * 取走本聚合自上次保存以来产生的领域事件并清空（Vernon 主流做法）。
     * 仓储保存聚合时调用：返回不可变快照，调用方遍历发布期间聚合再产生
     * 事件也不影响本批；重组（reconstitute）出来的聚合事件列表永远为空。
     */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> snapshot = List.copyOf(events);
        events.clear();
        return snapshot;
    }

    private static String generateOrderNo() {
        String timestamp = LocalDateTime.now().format(ORDER_NO_FORMAT);
        int suffix = ThreadLocalRandom.current().nextInt(1_000_000);
        return "OD" + timestamp + String.format("%06d", suffix);
    }
}
