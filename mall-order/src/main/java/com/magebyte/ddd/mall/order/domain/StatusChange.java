package com.magebyte.ddd.mall.order.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 状态变更（StatusChange）值对象：订单状态机上的一次迁移记录。
 *
 * <p>不可变、按值相等，随订单聚合一起持久化到 t_order_status_history。
 * {@code from} 允许为空——订单创建时从"无状态"进入待支付，是历史链的第一节；
 * 此后每一节的 from 必须等于上一节的 to，这条链在
 * {@link Order#reconstitute} 重组时反向校验，落库数据断链宁可抛出也不带回。
 *
 * @param from       迁移前状态；创建记录为 null，其余不允许为空
 * @param to         迁移后状态
 * @param reason     变更原因（用户下单 / 支付回调成功 / 商家发货 / 用户确认收货 / 用户主动取消 / 超时未支付自动取消）
 * @param operatedBy 操作人标识（user:1001、payment-callback、merchant:2001、system:timeout-job）
 * @param occurredAt 变更时间
 */
public record StatusChange(OrderStatus from, OrderStatus to, String reason,
                           String operatedBy, LocalDateTime occurredAt) {

    public StatusChange {
        Objects.requireNonNull(to, "目标状态不能为空");
        Objects.requireNonNull(occurredAt, "变更时间不能为空");
        Objects.requireNonNull(reason, "变更原因不能为空");
        Objects.requireNonNull(operatedBy, "操作人不能为空");
    }
}
