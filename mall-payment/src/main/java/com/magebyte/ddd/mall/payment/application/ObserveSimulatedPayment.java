package com.magebyte.ddd.mall.payment.application;

import com.magebyte.ddd.mall.payment.application.PaymentCallbackService.Receipt;
import com.magebyte.ddd.mall.payment.application.PaymentChannelQuery.Failure;
import com.magebyte.ddd.mall.payment.application.PaymentChannelQuery.Report;
import com.magebyte.ddd.mall.payment.domain.Payment;
import com.magebyte.ddd.mall.payment.domain.PaymentRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ObserveSimulatedPayment {
    private static final Logger log = LoggerFactory.getLogger(ObserveSimulatedPayment.class);
    private final PaymentRepository payments;
    private final PaymentChannelQuery channel;
    private final PaymentCallbackService callbacks;

    public ObserveSimulatedPayment(PaymentRepository payments, PaymentChannelQuery channel,
                                   PaymentCallbackService callbacks) {
        this.payments = payments;
        this.channel = channel;
        this.callbacks = callbacks;
    }

    public Optional<Receipt> query(String paymentNo) {
        return payments.find(paymentNo).map(payment -> {
            // 把远程读留在确认事务之外。回调到达时可独立完成确认。
            return switch (channel.find(payment.paymentNo())) {
                case Report report -> callbacks.receive(report.observation(), report.reportedResult());
                case Failure failure -> resultWithoutReport(paymentNo, failure);
            };
        });
    }

    private Receipt resultWithoutReport(String paymentNo, Failure failure) {
        log.warn("模拟查单未取得可用结果 paymentNo={} reason={}", paymentNo, failure);
        String status = payments.find(paymentNo).map(Payment::status).orElse(null);
        String result = switch (failure) {
            case QUERY_TIMEOUT, QUERY_UNAVAILABLE -> "NO_NEW_CONFIRMATION";
            case INVALID_CHANNEL_RESPONSE, QUERY_IDENTITY_MISMATCH -> "REJECTED";
        };
        return new Receipt(result, status, failure.name());
    }
}
