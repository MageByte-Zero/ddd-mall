package com.magebyte.ddd.mall.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 支付限界上下文（支撑域）启动类。
 *
 * <p>端口 8083；注册到 Nacos 的服务名为 mall-payment。
 * MapperScan 指向 infrastructure.persistence，第 17 讲落 Payment 聚合根与防腐层。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.magebyte.ddd.mall.payment.infrastructure.persistence")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
