package com.magebyte.ddd.mall.payment.application;

import com.magebyte.ddd.mall.payment.domain.Payment.Observation;

/** 查原支付单的渠道结果。未取得结果时没有渠道观察可以提交。 */
public interface PaymentChannelQuery {
    sealed interface Result permits Report, Failure {}

    record Report(Observation observation, String reportedResult) implements Result {}

    enum Failure implements Result {
        QUERY_TIMEOUT, QUERY_UNAVAILABLE, INVALID_CHANNEL_RESPONSE, QUERY_IDENTITY_MISMATCH
    }

    Result find(String paymentNo);
}
