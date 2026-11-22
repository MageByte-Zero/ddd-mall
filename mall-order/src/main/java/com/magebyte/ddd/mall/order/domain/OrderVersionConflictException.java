package com.magebyte.ddd.mall.order.domain;

/** 仓储保存的是过期订单快照，调用方可以重读后重新判断。 */
public final class OrderVersionConflictException extends OrderDomainException {
    public OrderVersionConflictException(Long orderId) {
        super("订单已被并发修改，请基于最新版本重试 (id=" + orderId + ")");
    }
}
