package com.magebyte.ddd.mall.order.application;

/**
 * 取消订单用例的入参。
 *
 * <p>{@code reason} 不是可选项：状态历史链要回答"这笔订单为什么没了"，
 * 用户主动取消和超时自动取消在运维看板上是两种不同的故事，靠这个字段区分。
 *
 * @param simulateReleaseFailure 教学开关：true 时在库存归还分支提交后停留再失败，
 *                               用于观察"取消"这个方向上的全局事务回滚。
 *                               和第 10 讲下单方向的 {@code simulateRollbackFailure}
 *                               是一对对称的教学开关，业务代码不该出现这种分支。
 */
public record CancelOrderCommand(
        String orderNo,
        String reason,
        String operatedBy,
        boolean simulateReleaseFailure) {

    public static CancelOrderCommand of(String orderNo, String reason, String operatedBy) {
        return new CancelOrderCommand(orderNo, reason, operatedBy, false);
    }
}
