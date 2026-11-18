package com.magebyte.ddd.mall.payment.infrastructure;

import com.magebyte.ddd.mall.payment.domain.OrderCheckout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OrderPaymentClient implements OrderCheckout {
    private final RestClient orders;

    public OrderPaymentClient(@Value("${ddd.payment.order-base-url}") String baseUrl,
                              @Value("${ddd.payment.handoff-token:}") String token) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(3000);
        this.orders = RestClient.builder().requestFactory(requestFactory).baseUrl(baseUrl)
                .defaultHeader("X-Payment-Handoff-Token", token).build();
    }

    @Override
    public Checkout checkout(String orderNo) {
        try {
            Checkout snapshot = orders.get().uri("/internal/payment-checkout/{orderNo}", orderNo)
                    .retrieve().body(Checkout.class);
            if (snapshot == null || !orderNo.equals(snapshot.orderNo())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法核对订单付款快照");
            }
            return snapshot;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在", e);
        } catch (HttpClientErrorException.UnprocessableEntity e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "该订单当前不可创建支付单", e);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "订单付款快照暂不可确认，请按原订单重试", e);
        }
    }
}
