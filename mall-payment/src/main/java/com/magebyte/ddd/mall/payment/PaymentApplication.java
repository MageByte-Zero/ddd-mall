package com.magebyte.ddd.mall.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 支付限界上下文（支撑域）启动类。
 *
 * <p>端口 8083；注册到 Nacos 的服务名为 mall-payment。
 * 支付仓储使用 Spring JDBC。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
