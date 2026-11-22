package com.magebyte.ddd.mall.payment.application;

import com.magebyte.ddd.mall.payment.domain.Payment;
import com.magebyte.ddd.mall.payment.domain.Payment.Observation;
import com.magebyte.ddd.mall.payment.domain.PaymentJournal;
import com.magebyte.ddd.mall.payment.domain.PaymentRepository;
import com.magebyte.ddd.mall.payment.domain.PaymentSucceeded;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCallbackService {
    private final PaymentRepository payments;
    private final PaymentJournal journal;

    public PaymentCallbackService(PaymentRepository payments, PaymentJournal journal) {
        this.payments = payments;
        this.journal = journal;
    }

    public record Receipt(String result, String paymentStatus, String reason) {}

    @Transactional
    public Receipt receive(Observation observation, String reportedResult) {
        Payment payment = payments.lock(observation.paymentNo()).orElse(null);
        if (payment == null) return review(observation, reportedResult, null, "UNKNOWN_PAYMENT");

        var assessment = payment.observe(observation, Instant.now().truncatedTo(ChronoUnit.MICROS));
        return switch (assessment.decision()) {
            case REPLAY -> new Receipt("ALREADY_CONFIRMED", payment.status(), null);
            case PENDING -> new Receipt("NO_NEW_CONFIRMATION", payment.status(), switch (observation.result()) {
                case ACCEPTED -> "CHANNEL_ACCEPTED";
                case PROCESSING -> "CHANNEL_PROCESSING";
                default -> null;
            });
            case CONFIRM -> confirm(assessment.payment(), observation, reportedResult);
            default -> review(observation, reportedResult, payment.status(), assessment.decision().name());
        };
    }

    private Receipt confirm(Payment confirmed, Observation observation, String reportedResult) {
        try {
            payments.confirm(confirmed);
        } catch (DuplicateKeyException conflict) {
            // The only unique field updated by confirmation is the channel transaction identity.
            return review(observation, reportedResult, "PENDING", "CHANNEL_TRANSACTION_IN_USE");
        }
        journal.appendSuccess(PaymentSucceeded.from(confirmed));
        return new Receipt("CONFIRMED", confirmed.status(), null);
    }

    private Receipt review(Observation observation, String reportedResult, String status, String reason) {
        journal.flagForReview(observation, reportedResult, reason);
        return new Receipt("REJECTED", status, reason);
    }
}
