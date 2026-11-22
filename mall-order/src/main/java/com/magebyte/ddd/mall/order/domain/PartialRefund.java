package com.magebyte.ddd.mall.order.domain;

import java.util.Objects;

/** 一笔订单内的退款申请快照。身份在同一订单内由 refundNo 确定。 */
public record PartialRefund(String refundNo, String lineId, int quantity, Money amount,
                            String reason, OrderStatus requestedFrom, RefundResult result) {
    public PartialRefund {
        if (refundNo == null || refundNo.isBlank() || !refundNo.equals(refundNo.strip()) || refundNo.length() > 64) {
            throw new OrderDomainException("退款申请号须为 1 至 64 字符，且首尾无空白");
        }
        if (lineId == null || lineId.isBlank() || quantity <= 0) {
            throw new OrderDomainException("部分退款须指定订单行和正整数数量");
        }
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new OrderDomainException("退款原因须为 1 至 200 字符");
        }
        if (requestedFrom != OrderStatus.PAID && requestedFrom != OrderStatus.SHIPPED) {
            throw new OrderDomainException("部分退款须在已付款或已发货时申请");
        }
        Objects.requireNonNull(amount, "退款金额不能为空");
        if (result != null) {
            if (!refundNo.equals(result.refundNo()) || !amount.equals(result.amount())) {
                throw new OrderDomainException("退款结果与原申请身份或金额不符");
            }
            if (result.outcome() == RefundResult.Outcome.REJECTED && result.reference().isBlank()) {
                throw new OrderDomainException("最终拒绝须有结果依据");
            }
        }
    }

    boolean sameRequest(String lineId, int quantity, String reason) {
        return this.lineId.equals(lineId) && this.quantity == quantity && this.reason.equals(reason);
    }

    boolean countsAgainstLimit() {
        return result == null || result.outcome() != RefundResult.Outcome.REJECTED;
    }

    PartialRefund withResult(RefundResult next) {
        Objects.requireNonNull(next, "退款结果不能为空");
        if (next.equals(result)) {
            return this;
        }
        if (result != null && result.outcome() != RefundResult.Outcome.UNKNOWN) {
            throw new OrderDomainException("退款结果与已确认的终态冲突，须核查");
        }
        return new PartialRefund(refundNo, lineId, quantity, amount, reason, requestedFrom, next);
    }
}
