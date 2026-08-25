package com.magebyte.ddd.mall.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 订单限界上下文（核心域）启动类。
 *
 * <p>端口 8084；注册到 Nacos 的服务名为 mall-order。
 * 不声明 @MapperScan：第 6 讲 OrderMapper 落地时再扫描 infrastructure.persistence。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
