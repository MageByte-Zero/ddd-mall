package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.magebyte.ddd.mall.order.domain.Address;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.Order;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderItem;
import com.magebyte.ddd.mall.order.domain.OrderRepository;
import com.magebyte.ddd.mall.order.domain.OrderStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓储的基础设施层实现。
 *
 * <p>依赖倒置的落点：领域层定义 {@link OrderRepository} 接口，
 * 本类实现它并持有 MyBatis-Plus Mapper。Spring 容器装配时把本实现
 * 注入给需要仓储的组件——领域层全程不 import 任何持久化技术。
 *
 * <p>翻译职责：聚合 ↔ 数据对象的双向转换都集中在这里。
 * 存入时拆成订单表 + 订单项表两张表；读出时重组回完整聚合并过一遍
 * {@link Order#reconstitute} 的不变量校验。
 */
@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    public OrderRepositoryImpl(OrderMapper orderMapper, OrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Override
    public Order save(Order order) {
        if (order.id() == null) {
            return insert(order);
        }
        return update(order);
    }

    @Override
    public Optional<Order> findById(Long id) {
        OrderDO orderDO = orderMapper.selectById(id);
        return toAggregate(orderDO);
    }

    @Override
    public Optional<Order> findByOrderNo(String orderNo) {
        OrderDO orderDO = orderMapper.selectOne(
                Wrappers.<OrderDO>lambdaQuery().eq(OrderDO::getOrderNo, orderNo));
        return toAggregate(orderDO);
    }

    private Order insert(Order order) {
        OrderDO orderDO = toOrderDO(order);
        orderMapper.insert(orderDO);
        for (OrderItem item : order.getItems()) {
            orderItemMapper.insert(toItemDO(orderDO.getId(), item));
        }
        return findById(orderDO.getId()).orElseThrow();
    }

    private Order update(Order order) {
        OrderDO orderDO = toOrderDO(order);
        orderDO.setId(order.id());
        orderDO.setVersion(order.version());
        // 乐观锁：updateById 自动带 version 条件，并发修改时影响行数为 0
        int affected = orderMapper.updateById(orderDO);
        if (affected == 0) {
            throw new OrderDomainException("订单已被并发修改，请基于最新版本重试 (id="
                    + order.id() + ")");
        }
        // 订单项在聚合内只增不删，简单起见整批对齐：先清后插
        orderItemMapper.delete(Wrappers.<OrderItemDO>lambdaQuery()
                .eq(OrderItemDO::getOrderId, order.id()));
        for (OrderItem item : order.getItems()) {
            orderItemMapper.insert(toItemDO(order.id(), item));
        }
        return findById(order.id()).orElseThrow();
    }

    private Optional<Order> toAggregate(OrderDO orderDO) {
        if (orderDO == null) {
            return Optional.empty();
        }
        List<OrderItemDO> itemDOs = orderItemMapper.selectList(
                Wrappers.<OrderItemDO>lambdaQuery()
                        .eq(OrderItemDO::getOrderId, orderDO.getId()));
        List<OrderItem> items = new ArrayList<>(itemDOs.size());
        for (OrderItemDO itemDO : itemDOs) {
            items.add(OrderItem.reconstitute(
                    itemDO.getId(),
                    itemDO.getProductId(),
                    itemDO.getSkuId(),
                    itemDO.getProductName(),
                    itemDO.getQuantity(),
                    Money.of(itemDO.getUnitPrice())));
        }
        return Optional.of(Order.reconstitute(
                orderDO.getId(),
                orderDO.getOrderNo(),
                orderDO.getUserId(),
                OrderStatus.valueOf(orderDO.getStatus()),
                items,
                Money.of(orderDO.getTotalAmount()),
                orderDO.getPaidAmount() == null ? null : Money.of(orderDO.getPaidAmount()),
                new Address(orderDO.getReceiverName(),
                        orderDO.getReceiverPhone(),
                        orderDO.getReceiverAddress()),
                orderDO.getVersion(),
                orderDO.getCreatedAt(),
                orderDO.getUpdatedAt()));
    }

    private OrderDO toOrderDO(Order order) {
        OrderDO orderDO = new OrderDO();
        orderDO.setOrderNo(order.orderNo());
        orderDO.setUserId(order.userId());
        orderDO.setStatus(order.status().name());
        orderDO.setTotalAmount(order.totalAmount().amount());
        orderDO.setPaidAmount(order.paidAmount() == null ? null : order.paidAmount().amount());
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
        itemDO.setProductId(item.productId());
        itemDO.setSkuId(item.skuId());
        itemDO.setProductName(item.productName());
        itemDO.setQuantity(item.quantity());
        itemDO.setUnitPrice(item.unitPrice().amount());
        itemDO.setSubtotal(item.subtotal().amount());
        return itemDO;
    }
}
