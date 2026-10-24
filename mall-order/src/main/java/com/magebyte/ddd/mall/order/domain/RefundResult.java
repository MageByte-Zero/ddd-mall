package com.magebyte.ddd.mall.order.domain;

import java.util.Objects;

/** 已翻译到订单语言的结果值；不负责验证渠道签名或发起资金操作。 */
public record RefundResult(String refundNo, Money amount, Outcome outcome, String reference) {
    public enum Outcome { UNKNOWN, REJECTED, SUCCEEDED }
    public RefundResult {
        if (refundNo == null || refundNo.isBlank() || refundNo.length() > 64)
            throw new OrderDomainException("退款申请号须为1至64字符");
        Objects.requireNonNull(amount, "退款金额不能为空");
        Objects.requireNonNull(outcome, "退款结果不能为空");
        reference = reference == null ? "" : reference;
        if (reference.length() > 128 || (outcome == Outcome.SUCCEEDED && reference.isBlank()))
            throw new OrderDomainException("成功结果须有凭证，结果引用最多128字符");
    }
}
