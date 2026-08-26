package com.magebyte.ddd.mall.order.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态（OrderStatus）。
 *
 * <p>七个状态、八条合法迁移，与订单需求文档（fixtures/requirements/
 * order-creation.md）的状态机逐条一致，是状态机的唯一实现来源：
 *
 * <pre>
 * PENDING_PAY → PAID → SHIPPED → RECEIVED
 *      ↓
 * CANCELLED
 *      ↓（PAID / SHIPPED 均可发起）
 * REFUND_REQUESTED → REFUNDED
 * </pre>
 *
 * <p>终态：RECEIVED、CANCELLED、REFUNDED——没有任何出边。
 * 完整状态机行为（状态历史落库、非法迁移的对外响应）在第 7 讲补齐；
 * 本讲只立迁移规则本身，供聚合根的 markPaid / cancel 使用。
 */
public enum OrderStatus {

    /** 待支付：订单创建后的初始状态。 */
    PENDING_PAY,
    /** 已支付。 */
    PAID,
    /** 已发货。 */
    SHIPPED,
    /** 已收货（终态）。 */
    RECEIVED,
    /** 已取消（终态）。 */
    CANCELLED,
    /** 退款申请中。 */
    REFUND_REQUESTED,
    /** 已退款（终态）。 */
    REFUNDED;

    private static final Map<OrderStatus, Set<OrderStatus>> LEGAL_TRANSITIONS;

    static {
        Map<OrderStatus, Set<OrderStatus>> map = new EnumMap<>(OrderStatus.class);
        map.put(PENDING_PAY, Set.of(PAID, CANCELLED));
        map.put(PAID, Set.of(SHIPPED, REFUND_REQUESTED));
        map.put(SHIPPED, Set.of(RECEIVED, REFUND_REQUESTED));
        map.put(REFUND_REQUESTED, Set.of(REFUNDED));
        LEGAL_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    /**
     * 判断当前状态能否迁移到目标状态。
     * 不在合法迁移表里的一律拒绝——包括终态出边和跳级迁移。
     */
    public boolean canTransitionTo(OrderStatus target) {
        return LEGAL_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
