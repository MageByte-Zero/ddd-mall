package com.magebyte.ddd.mall.order.domain;

import java.util.Objects;

/** 订单聚合内的退款申请实体；身份不随结果改变，无独立仓储或公开修改入口。 */
public final class Refund {
    private final String refundNo;
    private final Money amount;
    private final String reason;
    private final OrderStatus requestedFrom;
    private final RefundResult result;

    private Refund(String refundNo, Money amount, String reason, OrderStatus requestedFrom, RefundResult result) {
        if (refundNo == null || refundNo.isBlank() || refundNo.length() > 64)
            throw new OrderDomainException("退款申请号须为1至64字符");
        if (reason == null || reason.isBlank() || reason.length() > 200)
            throw new OrderDomainException("退款原因须为1至200字符");
        if (requestedFrom != OrderStatus.PAID && requestedFrom != OrderStatus.SHIPPED)
            throw new OrderDomainException("退款申请前必须已支付或已发货");
        this.refundNo = refundNo;
        this.amount = Objects.requireNonNull(amount, "退款金额不能为空");
        this.reason = reason;
        this.requestedFrom = requestedFrom;
        if (result != null) assertResultMatches(refundNo, amount, result);
        this.result = result;
    }
    static Refund request(String refundNo, Money amount, String reason, OrderStatus from) {
        return new Refund(refundNo, amount, reason, from, null);
    }
    /** 仓储还原实体；聚合还会检查其与订单状态、历史、实付的关系。 */
    public static Refund reconstitute(String refundNo, Money amount, String reason,
                                      OrderStatus from, RefundResult result) {
        return new Refund(refundNo, amount, reason, from, result);
    }
    Refund withResult(RefundResult next) {
        Objects.requireNonNull(next, "退款结果不能为空");
        assertResultMatches(refundNo, amount, next);
        if (result != null && result.outcome() == RefundResult.Outcome.SUCCEEDED && !result.equals(next))
            throw new OrderDomainException("已退款事实不能被不同结果覆盖");
        return new Refund(refundNo, amount, reason, requestedFrom, next);
    }
    boolean sameRequest(String no, Money money, String why) {
        return refundNo.equals(no) && amount.equals(money) && reason.equals(why);
    }
    private static void assertResultMatches(String no, Money money, RefundResult result) {
        if (!no.equals(result.refundNo()) || !money.equals(result.amount()))
            throw new OrderDomainException("退款结果的申请身份或金额不匹配");
    }
    public String refundNo() { return refundNo; }
    public Money amount() { return amount; }
    public String reason() { return reason; }
    public OrderStatus requestedFrom() { return requestedFrom; }
    public RefundResult result() { return result; }
}
