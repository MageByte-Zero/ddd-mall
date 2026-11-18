package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.commons.security.MessageAuthentication;
import com.magebyte.ddd.mall.order.application.AcceptPaymentService;
import com.magebyte.ddd.mall.order.application.AcceptPaymentService.Checkout;
import com.magebyte.ddd.mall.order.application.AcceptPaymentService.Outcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PaymentHandoffController {
    private final AcceptPaymentService service;
    private final String token;

    public PaymentHandoffController(AcceptPaymentService service,
                                    @Value("${ddd.payment.handoff-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @GetMapping("/internal/payment-checkout/{orderNo}")
    public Checkout checkout(@RequestHeader(value = "X-Payment-Handoff-Token", required = false) String supplied,
                             @PathVariable String orderNo) {
        authorize(supplied);
        return service.checkout(orderNo);
    }

    @GetMapping("/internal/payment-outcomes/{paymentNo}")
    public Outcome outcome(@RequestHeader(value = "X-Payment-Handoff-Token", required = false) String supplied,
                           @PathVariable String paymentNo) {
        authorize(supplied);
        return service.findOutcome(paymentNo).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void authorize(String supplied) {
        if (!MessageAuthentication.matches(token, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
