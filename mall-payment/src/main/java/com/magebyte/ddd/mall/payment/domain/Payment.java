package com.magebyte.ddd.mall.payment.domain;

import java.time.Instant;
import java.util.Objects;

/** 一笔付款的确认规则只由支付单执行。 */
public record Payment(String paymentNo, String orderNo, PaymentAmount amount, String channel,
                      String channelTxnNo, Instant confirmedAt) {

    public enum ChannelResult { CONFIRMED, UNKNOWN, UNRECOGNIZED }

    public record Observation(String paymentNo, String orderNo, String channelTxnNo,
                              long amountMinor, String currency, ChannelResult result) {}
    public record Assessment(Decision decision, Payment payment) {}

    public enum Decision {
        CONFIRM, REPLAY, PENDING, ORDER_MISMATCH, AMOUNT_MISMATCH,
        UNSUPPORTED_RESULT, TRANSACTION_REQUIRED, CONFLICTING_SUCCESS
    }

    public String status() {
        return confirmedAt == null ? "PENDING" : "SUCCEEDED";
    }

    public Assessment observe(Observation observation, Instant time) {
        Decision decision = assess(observation.orderNo(), observation.amountMinor(), observation.currency(),
                observation.channelTxnNo(), observation.result());
        Payment next = decision == Decision.CONFIRM
                ? new Payment(paymentNo, orderNo, amount, channel, observation.channelTxnNo(),
                              Objects.requireNonNull(time)) : this;
        return new Assessment(decision, next);
    }

    private Decision assess(String claimedOrder, long minor, String currency, String txn, ChannelResult result) {
        if (!orderNo.equals(claimedOrder)) return Decision.ORDER_MISMATCH;
        if (amount.minorUnits() != minor || !amount.currency().equals(currency)) {
            return Decision.AMOUNT_MISMATCH;
        }
        if (result == ChannelResult.UNKNOWN) return Decision.PENDING;
        if (result != ChannelResult.CONFIRMED) return Decision.UNSUPPORTED_RESULT;
        if (txn == null || txn.isBlank()) return Decision.TRANSACTION_REQUIRED;
        if (confirmedAt == null) return Decision.CONFIRM;
        return Objects.equals(channelTxnNo, txn) ? Decision.REPLAY : Decision.CONFLICTING_SUCCESS;
    }

}
