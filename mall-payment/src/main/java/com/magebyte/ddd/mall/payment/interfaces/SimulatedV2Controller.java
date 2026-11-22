package com.magebyte.ddd.mall.payment.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.magebyte.ddd.mall.payment.application.ObserveSimulatedPayment;
import com.magebyte.ddd.mall.payment.application.PaymentCallbackService;
import com.magebyte.ddd.mall.payment.application.PaymentCallbackService.Receipt;
import com.magebyte.ddd.mall.payment.infrastructure.SimulatedV2Protocol;
import com.magebyte.ddd.mall.payment.infrastructure.SimulatedV2Protocol.InvalidReport;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
@Validated
public class SimulatedV2Controller {
    private static final Logger log = LoggerFactory.getLogger(SimulatedV2Controller.class);
    private final PaymentCallbackService callbacks;
    private final ObserveSimulatedPayment query;
    private final String callbackToken;
    private final String operatorToken;

    public SimulatedV2Controller(PaymentCallbackService callbacks, ObserveSimulatedPayment query,
                                 @Value("${ddd.payment.simulated-v2-callback-token:}") String callbackToken,
                                 @Value("${ddd.payment.handoff-token:}") String operatorToken) {
        this.callbacks = callbacks;
        this.query = query;
        this.callbackToken = callbackToken;
        this.operatorToken = operatorToken;
    }

    @PostMapping("/callbacks/simulated/v2")
    public ResponseEntity<Receipt> callback(
            @RequestHeader(value = "X-Simulated-Callback-Token", required = false) String token,
            @RequestBody JsonNode body) {
        PaymentController.authorize(callbackToken, token);
        try {
            var report = SimulatedV2Protocol.translate(body);
            return respond(callbacks.receive(report.observation(), report.reportedResult()));
        } catch (InvalidReport invalid) {
            log.warn("V2 模拟回调报文无效 paymentNo={} reason={}",
                    SimulatedV2Protocol.merchantRef(body), invalid.getMessage());
            return ResponseEntity.badRequest().body(new Receipt("REJECTED", null, invalid.getMessage()));
        }
    }

    @PostMapping("/{paymentNo}/simulated-query")
    public ResponseEntity<Receipt> query(
            @RequestHeader(value = "X-Payment-Handoff-Token", required = false) String token,
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,48}") String paymentNo) {
        PaymentController.authorize(operatorToken, token);
        return respond(query.query(paymentNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private static ResponseEntity<Receipt> respond(Receipt receipt) {
        return ResponseEntity.status("REJECTED".equals(receipt.result()) ? 422 : 200).body(receipt);
    }
}
