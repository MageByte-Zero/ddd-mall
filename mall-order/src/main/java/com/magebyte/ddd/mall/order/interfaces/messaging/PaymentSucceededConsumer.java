package com.magebyte.ddd.mall.order.interfaces.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.commons.security.MessageAuthentication;
import com.magebyte.ddd.mall.order.application.AcceptPaymentService;
import com.magebyte.ddd.mall.order.application.AcceptPaymentService.PaymentFact;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ddd.payment.consumer-enabled", havingValue = "true")
@RocketMQMessageListener(topic = "${ddd.payment.events-topic:payment-events}", selectorExpression = "PaymentSucceeded",
        consumerGroup = "${ddd.payment.consumer-group:mall-order-payment-v1}", maxReconsumeTimes = 5)
public class PaymentSucceededConsumer implements RocketMQListener<String> {
    private final ObjectMapper json;
    private final AcceptPaymentService service;
    private final String token;

    public PaymentSucceededConsumer(ObjectMapper json, AcceptPaymentService service,
                                    @Value("${ddd.payment.handoff-token:}") String token) {
        this.json = json;
        this.service = service;
        if (token.isBlank()) throw new IllegalStateException("订阅支付事实需要配置交接密钥");
        this.token = token;
    }

    private record Envelope(String payload, String signature) {}

    @Override
    public void onMessage(String message) {
        try {
            Envelope envelope = json.readValue(message, Envelope.class);
            if (envelope.payload() == null
                    || !MessageAuthentication.matches(MessageAuthentication.sign(token, envelope.payload()),
                                                      envelope.signature())) {
                throw new IllegalArgumentException("支付事实来源未通过验证");
            }
            service.accept(json.readValue(envelope.payload(), PaymentFact.class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法读取支付事实", e);
        }
    }
}
