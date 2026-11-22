package com.magebyte.ddd.mall.payment.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.magebyte.ddd.mall.payment.application.PaymentChannelQuery;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class SimulatedV2QueryClient implements PaymentChannelQuery {
    private final RestClient client;

    public SimulatedV2QueryClient(@Value("${ddd.payment.simulated-v2-query-base-url:}") String baseUrl,
                                  @Value("${ddd.payment.simulated-v2-query-timeout-ms:1000}") int timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("模拟查单超时必须是正数");
        var requests = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requests.setConnectTimeout(timeoutMs);
        requests.setReadTimeout(timeoutMs);
        client = baseUrl.isBlank() ? null : RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
    }

    @Override
    public Result find(String paymentNo) {
        if (client == null) return Failure.QUERY_UNAVAILABLE;
        try {
            var response = client.get().uri("/payments/{paymentNo}", paymentNo).retrieve().toEntity(JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful()) return Failure.QUERY_UNAVAILABLE;
            Report report = SimulatedV2Protocol.translate(response.getBody());
            return paymentNo.equals(report.observation().paymentNo()) ? report : Failure.QUERY_IDENTITY_MISMATCH;
        } catch (ResourceAccessException error) {
            return error.contains(SocketTimeoutException.class) ? Failure.QUERY_TIMEOUT : Failure.QUERY_UNAVAILABLE;
        } catch (RestClientResponseException error) {
            // HTTP 错误本身无法证明渠道收款或拒绝该付款。
            return Failure.QUERY_UNAVAILABLE;
        } catch (SimulatedV2Protocol.InvalidReport | RestClientException error) {
            return Failure.INVALID_CHANNEL_RESPONSE;
        }
    }
}
