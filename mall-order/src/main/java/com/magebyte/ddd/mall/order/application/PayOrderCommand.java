package com.magebyte.ddd.mall.order.application;

/**
 * 支付订单用例的入参。
 *
 * <p>{@code paidAmount} 用字符串收，是因为 JSON 里的 {@code 198.00} 一旦经过
 * double 中转，尾数就可能变成 {@code 198.00000000000003}；到了领域层再转
 * {@code Money}（BigDecimal）。跨协议传金额，宁可多一次显式转换。
 *
 * <p>{@code operatedBy} 记录"谁把这笔订单置为已支付"：真实系统里是支付 BC 的回调身份，
 * 本讲由调用方传入，落进状态历史链——事后审计能回答"这笔支付是谁确认的"。
 */
public record PayOrderCommand(
        String orderNo,
        String paidAmount,
        String operatedBy) {

    public static PayOrderCommand of(String orderNo, String paidAmount, String operatedBy) {
        return new PayOrderCommand(orderNo, paidAmount, operatedBy);
    }
}
