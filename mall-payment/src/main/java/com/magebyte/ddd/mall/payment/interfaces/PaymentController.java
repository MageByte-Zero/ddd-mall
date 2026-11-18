package com.magebyte.ddd.mall.payment.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.magebyte.ddd.mall.commons.security.MessageAuthentication;
import com.magebyte.ddd.mall.payment.application.PaymentApplicationService;
import com.magebyte.ddd.mall.payment.application.PaymentCallbackService;
import com.magebyte.ddd.mall.payment.application.PaymentCallbackService.Receipt;
import com.magebyte.ddd.mall.payment.domain.Payment;
import com.magebyte.ddd.mall.payment.domain.Payment.ChannelResult;
import com.magebyte.ddd.mall.payment.domain.Payment.Observation;
import com.magebyte.ddd.mall.payment.domain.PaymentRepository;
import com.magebyte.ddd.mall.payment.infrastructure.PaymentOutboxRelay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
@Validated
public class PaymentController {
    private static final String ID = "[A-Za-z0-9_-]+";
    private final PaymentApplicationService service;
    private final PaymentCallbackService callbackService;
    private final PaymentRepository payments;
    private final PaymentOutboxRelay outbox;
    private final String operatorToken;
    private final String callbackToken;

    public PaymentController(PaymentApplicationService service, PaymentCallbackService callbackService,
                             PaymentRepository payments, PaymentOutboxRelay outbox,
                             @Value("${ddd.payment.handoff-token:}") String operatorToken,
                             @Value("${ddd.payment.callback-token:}") String callbackToken) {
        this.service = service;
        this.callbackService = callbackService;
        this.payments = payments;
        this.outbox = outbox;
        this.operatorToken = operatorToken;
        this.callbackToken = callbackToken;
    }

    public record CreateRequest(@NotBlank @Size(max = 64) @Pattern(regexp = ID) String orderNo) {}

    public record CallbackRequest(
            @NotBlank @Size(max = 48) @Pattern(regexp = ID) String paymentNo,
            @NotBlank @Size(max = 64) @Pattern(regexp = ID) String orderNo,
            @Size(max = 80) @Pattern(regexp = ID) String channelTxnNo,
            @NotNull JsonNode amountMinor, @NotBlank @Size(max = 8) String currency,
            @NotBlank @Size(max = 32) String result) {}

    public record PaymentView(String paymentNo, String orderNo, long amountMinor, String currency,
                              String status, String channelTxnNo, Instant confirmedAt, String deliveryStatus) {}

    @PostMapping
    public PaymentView create(@RequestHeader(value = "X-Payment-Handoff-Token", required = false) String token,
                              @Valid @RequestBody CreateRequest request) {
        authorize(operatorToken, token);
        return view(service.create(request.orderNo()));
    }

    @GetMapping("/{paymentNo}")
    public PaymentView get(@RequestHeader(value = "X-Payment-Handoff-Token", required = false) String token,
                           @PathVariable String paymentNo) {
        authorize(operatorToken, token);
        return view(payments.find(paymentNo).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/callbacks/simulated")
    public ResponseEntity<Receipt> callback(
            @RequestHeader(value = "X-Simulated-Callback-Token", required = false) String token,
            @Valid @RequestBody CallbackRequest request) {
        authorize(callbackToken, token);
        if (!request.amountMinor().isIntegralNumber() || !request.amountMinor().canConvertToLong()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountMinor 必须是整数分");
        }
        ChannelResult translated = switch (request.result()) {
            case "SUCCESS" -> ChannelResult.CONFIRMED;
            case "UNKNOWN" -> ChannelResult.UNKNOWN;
            default -> ChannelResult.UNRECOGNIZED;
        };
        Receipt receipt = callbackService.receive(new Observation(request.paymentNo(), request.orderNo(),
                request.channelTxnNo(), request.amountMinor().longValue(), request.currency(), translated), request.result());
        return ResponseEntity.status("REJECTED".equals(receipt.result()) ? 422 : 200).body(receipt);
    }

    @PostMapping("/{paymentNo}/redelivery")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void redeliver(@RequestHeader(value = "X-Payment-Handoff-Token", required = false) String token,
                          @PathVariable String paymentNo) {
        authorize(operatorToken, token);
        if (!outbox.requeue(paymentNo)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有可重放的支付成功事实");
        }
    }

    private PaymentView view(Payment payment) {
        return new PaymentView(payment.paymentNo(), payment.orderNo(), payment.amount().minorUnits(),
                payment.amount().currency(), payment.status(), payment.channelTxnNo(),
                payment.confirmedAt(), payment.confirmedAt() == null ? null : outbox.status(payment.paymentNo()));
    }

    private static void authorize(String expected, String token) {
        if (!MessageAuthentication.matches(expected, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
