package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import com.magebyte.ddd.mall.order.domain.OrderVersionConflictException;
import com.magebyte.ddd.mall.order.domain.PartialRefund;
import com.magebyte.ddd.mall.order.domain.StatusChange;
import com.magebyte.ddd.mall.order.domain.Refund;
import com.magebyte.ddd.mall.order.domain.RefundResult;
import com.magebyte.ddd.mall.order.domain.event.DomainEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单仓储的基础设施层实现。
 *
 * <p>依赖倒置的落点：领域层定义 {@link OrderRepository} 接口，
 * 本类实现它并持有 MyBatis-Plus Mapper。Spring 容器装配时把本实现
 * 注入给需要仓储的组件——领域层全程不 import 任何持久化技术。
 *
 * <p>翻译职责：聚合 ↔ 数据对象的双向转换都集中在这里。
 * 存入时拆成订单根记录和从属记录；读出时重组回完整聚合并过一遍
 * {@link Order#reconstitute} 的不变量校验。
 *
 * <p>Outbox 模式（第 9 讲）：聚合取出的领域事件不再在事务提交后直接发 MQ，
 * 而是和订单数据写在<b>同一个本地事务</b>里——业务表写一行，t_outbox_event
 * 也写一行，要成一起成、要回滚一起回滚。事务提交后由
 * {@code infrastructure.messaging.OutboxEventRelay} 轮询投递到 RocketMQ。
 * 这样"数据库写"和"消息发送"两个系统的两次提交，被降维成一个数据库的
 * 一次本地事务：进程在提交后、发送前崩溃也不丢事件（行已在库里，
 * 中继器重启后继续捞）；事务回滚则 outbox 行随业务数据一起消失，
 * 不会产生幽灵事件。
 */
@Repository
public class OrderRepositoryImpl implements OrderRepository {

    /** outbox 行的聚合类型：本仓储只管订单聚合。 */
    private static final String AGGREGATE_TYPE_ORDER = "Order";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusHistoryMapper statusHistoryMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;

    @Value("${ddd.payment.new-orders-enabled:false}")
    private boolean paymentManagedForNewOrders;

    public OrderRepositoryImpl(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                               OrderStatusHistoryMapper statusHistoryMapper,
                               OutboxEventMapper outboxEventMapper,
                               ObjectMapper objectMapper, JdbcClient jdbc) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.statusHistoryMapper = statusHistoryMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public Order save(Order order) {
        // 取走事件快照：事件从聚合内存中清空，与第 8 讲相同
        List<DomainEvent> pendingEvents = order.pullEvents();
        Order saved = order.id() == null ? insert(order) : update(order);
        // 事件与业务数据写在同一个事务里：本方法整体处于调用方的事务中，
        // 下面这些 insert 与订单/订单项/历史的 insert 同生共死
        appendOutboxEvents(pendingEvents);
        return saved;
    }

    /**
     * 把本批事件写成 outbox 行。payload 就是发往 MQ 的消息体 JSON
     * （与中继器投递、订阅方收到的消息体逐字节一致），事件名落 event_type
     * （= 消息 tag），事件 ID 落 event_id（= 消息 keys，表内唯一）。
     */
    private void appendOutboxEvents(List<DomainEvent> events) {
        for (DomainEvent event : events) {
            OutboxEventDO row = new OutboxEventDO();
            row.setEventId(event.eventId());
            row.setAggregateType(AGGREGATE_TYPE_ORDER);
            row.setAggregateId(event.orderNo());
            row.setEventType(event.eventName());
            row.setPayload(toJson(event));
            row.setStatus(OutboxEventDO.STATUS_PENDING);
            row.setRetryCount(0);
            outboxEventMapper.insert(row);
        }
    }

    private String toJson(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 事件 record 全是固定字段，正常不会走到；走到说明编程错误，直接 fail 事务
            throw new IllegalStateException("领域事件序列化失败: " + event.eventName(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(Long id) {
        OrderDO orderDO = orderMapper.selectById(id);
        return toAggregate(orderDO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findByOrderNo(String orderNo) {
        OrderDO orderDO = orderMapper.selectOne(
                Wrappers.<OrderDO>lambdaQuery().eq(OrderDO::getOrderNo, orderNo));
        return toAggregate(orderDO);
    }

    private Order insert(Order order) {
        OrderDO orderDO = toOrderDO(order);
        orderDO.setPaymentManaged(paymentManagedForNewOrders);
        orderMapper.insert(orderDO);
        for (OrderItem item : order.getItems()) {
            orderItemMapper.insert(toItemDO(orderDO.getId(), item));
        }
        for (StatusChange change : order.statusHistory()) {
            statusHistoryMapper.insert(toHistoryDO(orderDO.getId(), change));
        }
        savePartialRefunds(orderDO.getId(), order.partialRefunds());
        return findById(orderDO.getId()).orElseThrow();
    }

    private Order update(Order order) {
        OrderDO orderDO = toOrderDO(order);
        orderDO.setId(order.id());
        orderDO.setVersion(order.version());
        // 乐观锁：updateById 自动带 version 条件，并发修改时影响行数为 0
        int affected = orderMapper.updateById(orderDO);
        if (affected == 0) {
            throw new OrderVersionConflictException(order.id());
        }
        // 已有行的成交快照不可变；状态更新只插新行，保留存量记录和行身份。
        for (OrderItem item : order.getItems()) {
            if (item.id() == null) {
                orderItemMapper.insert(toItemDO(order.id(), item));
            }
        }
        // 状态历史只增不改：库里已有几节，就只追加新增的尾段
        List<StatusChange> history = order.statusHistory();
        long persisted = statusHistoryMapper.selectCount(
                Wrappers.<OrderStatusHistoryDO>lambdaQuery()
                        .eq(OrderStatusHistoryDO::getOrderId, order.id()));
        for (int i = (int) persisted; i < history.size(); i++) {
            statusHistoryMapper.insert(toHistoryDO(order.id(), history.get(i)));
        }
        savePartialRefunds(order.id(), order.partialRefunds());
        return findById(order.id()).orElseThrow();
    }

    private Optional<Order> toAggregate(OrderDO orderDO) {
        if (orderDO == null) {
            return Optional.empty();
        }
        List<OrderItemDO> itemDOs = orderItemMapper.selectList(
                Wrappers.<OrderItemDO>lambdaQuery()
                        .eq(OrderItemDO::getOrderId, orderDO.getId())
                        .orderByAsc(OrderItemDO::getId));
        List<OrderItem> items = new ArrayList<>(itemDOs.size());
        for (OrderItemDO itemDO : itemDOs) {
            items.add(OrderItem.reconstitute(
                    itemDO.getId(),
                    itemDO.getLineId(),
                    itemDO.getReservationNo(),
                    itemDO.getPurchaseRequestNo(),
                    itemDO.getProductId(),
                    itemDO.getSkuId(),
                    itemDO.getProductName(),
                    itemDO.getQuantity(),
                    Money.of(itemDO.getUnitPrice())));
        }
        List<OrderStatusHistoryDO> historyDOs = statusHistoryMapper.selectList(
                Wrappers.<OrderStatusHistoryDO>lambdaQuery()
                        .eq(OrderStatusHistoryDO::getOrderId, orderDO.getId())
                        .orderByAsc(OrderStatusHistoryDO::getId));
        List<StatusChange> history = new ArrayList<>(historyDOs.size());
        for (OrderStatusHistoryDO historyDO : historyDOs) {
            history.add(new StatusChange(
                    historyDO.getFromStatus() == null
                            ? null : OrderStatus.valueOf(historyDO.getFromStatus()),
                    OrderStatus.valueOf(historyDO.getToStatus()),
                    historyDO.getReason(),
                    historyDO.getOperatedBy(),
                    historyDO.getCreatedAt()));
        }
        return Optional.of(Order.reconstitute(
                orderDO.getId(),
                orderDO.getOrderNo(),
                orderDO.getUserId(),
                OrderStatus.valueOf(orderDO.getStatus()),
                items,
                history,
                Money.of(orderDO.getTotalAmount()),
                orderDO.getPaidAmount() == null ? null : Money.of(orderDO.getPaidAmount()),
                new Address(orderDO.getReceiverName(),
                        orderDO.getReceiverPhone(),
                        orderDO.getReceiverAddress()),
                orderDO.getVersion(),
                orderDO.getCreatedAt(),
                orderDO.getUpdatedAt(),
                toRefund(orderDO),
                loadPartialRefunds(orderDO.getId())));
    }

    private List<PartialRefund> loadPartialRefunds(long orderId) {
        return jdbc.sql("""
                SELECT refund_no, line_id, quantity, amount, reason, requested_from, outcome, result_reference
                FROM t_order_partial_refund WHERE order_id = ? ORDER BY refund_no
                """).param(orderId).query((row, index) -> {
                    String refundNo = row.getString("refund_no");
                    Money amount = Money.of(row.getBigDecimal("amount"));
                    String outcome = row.getString("outcome");
                    RefundResult result = outcome == null ? null : new RefundResult(refundNo, amount,
                            RefundResult.Outcome.valueOf(outcome), row.getString("result_reference"));
                    return new PartialRefund(refundNo, row.getString("line_id"), row.getInt("quantity"),
                            amount, row.getString("reason"), OrderStatus.valueOf(row.getString("requested_from")), result);
                }).list();
    }

    private void savePartialRefunds(long orderId, List<PartialRefund> refunds) {
        if (refunds.isEmpty()) {
            return;
        }
        Map<String, PartialRefund> persisted = new HashMap<>();
        loadPartialRefunds(orderId).forEach(refund -> persisted.put(refund.refundNo(), refund));
        for (PartialRefund refund : refunds) {
            PartialRefund previous = persisted.get(refund.refundNo());
            RefundResult result = refund.result();
            if (previous == null) {
                jdbc.sql("""
                        INSERT INTO t_order_partial_refund
                            (order_id, refund_no, line_id, quantity, amount, reason, requested_from, outcome, result_reference)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """).params(orderId, refund.refundNo(), refund.lineId(), refund.quantity(),
                        refund.amount().amount(), refund.reason(), refund.requestedFrom().name(),
                        result == null ? null : result.outcome().name(), result == null ? null : result.reference()).update();
            } else if (!refund.equals(previous)) {
                jdbc.sql("""
                        UPDATE t_order_partial_refund SET outcome = ?, result_reference = ?
                        WHERE order_id = ? AND refund_no = ?
                        """).params(result.outcome().name(), result.reference(), orderId, refund.refundNo()).update();
            }
        }
    }

    private Refund toRefund(OrderDO row) {
        if (row.getRefundNo() == null) return null;
        Money amount = Money.of(row.getRefundAmount());
        RefundResult result = row.getRefundOutcome() == null ? null : new RefundResult(
                row.getRefundNo(), amount, RefundResult.Outcome.valueOf(row.getRefundOutcome()), row.getRefundReference());
        return Refund.reconstitute(row.getRefundNo(), amount, row.getRefundReason(),
                OrderStatus.valueOf(row.getRefundFromStatus()), result);
    }

    private OrderDO toOrderDO(Order order) {
        OrderDO orderDO = new OrderDO();
        orderDO.setOrderNo(order.orderNo());
        orderDO.setUserId(order.userId());
        orderDO.setStatus(order.status().name());
        orderDO.setTotalAmount(order.totalAmount().amount());
        orderDO.setPaidAmount(order.paidAmount() == null ? null : order.paidAmount().amount());
        if (order.refund() != null) {
            Refund refund = order.refund();
            orderDO.setRefundNo(refund.refundNo());
            orderDO.setRefundAmount(refund.amount().amount());
            orderDO.setRefundReason(refund.reason());
            orderDO.setRefundFromStatus(refund.requestedFrom().name());
            if (refund.result() != null) {
                orderDO.setRefundOutcome(refund.result().outcome().name());
                orderDO.setRefundReference(refund.result().reference());
            }
        }
        orderDO.setReceiverName(order.address().receiverName());
        orderDO.setReceiverPhone(order.address().receiverPhone());
        orderDO.setReceiverAddress(order.address().receiverAddress());
        orderDO.setCreatedAt(order.createdAt());
        orderDO.setUpdatedAt(order.updatedAt());
        return orderDO;
    }

    private OrderItemDO toItemDO(Long orderId, OrderItem item) {
        OrderItemDO itemDO = new OrderItemDO();
        itemDO.setOrderId(orderId);
        itemDO.setLineId(item.lineId());
        itemDO.setReservationNo(item.reservationNo());
        itemDO.setPurchaseRequestNo(item.purchaseRequestNo());
        itemDO.setProductId(item.productId());
        itemDO.setSkuId(item.skuId());
        itemDO.setProductName(item.productName());
        itemDO.setQuantity(item.quantity());
        itemDO.setUnitPrice(item.unitPrice().amount());
        itemDO.setSubtotal(item.subtotal().amount());
        return itemDO;
    }

    private OrderStatusHistoryDO toHistoryDO(Long orderId, StatusChange change) {
        OrderStatusHistoryDO historyDO = new OrderStatusHistoryDO();
        historyDO.setOrderId(orderId);
        historyDO.setFromStatus(change.from() == null ? null : change.from().name());
        historyDO.setToStatus(change.to().name());
        historyDO.setReason(change.reason());
        historyDO.setOperatedBy(change.operatedBy());
        historyDO.setCreatedAt(change.occurredAt());
        return historyDO;
    }
}
