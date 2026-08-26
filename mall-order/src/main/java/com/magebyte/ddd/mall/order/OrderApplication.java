package com.magebyte.ddd.mall.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 订单限界上下文（核心域）启动类。
 *
 * <p>端口 8084；注册到 Nacos 的服务名为 mall-order。
 *
 * <p>@MapperScan 随第 6 讲首个 Mapper（OrderMapper）落地而声明，
 * 扫描路径指向真实存在的基础设施层持久化包——不预支未来的路径。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.magebyte.ddd.mall.order.infrastructure.persistence")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
