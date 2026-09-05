package com.magebyte.ddd.mall.inventory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 库存限界上下文（支撑域）启动类。
 *
 * <p>端口 8082；注册到 Nacos 的服务名为 mall-inventory。
 *
 * <p>@MapperScan 随第 10 讲首个 Mapper（InventoryMapper）落地而声明。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.magebyte.ddd.mall.inventory.infrastructure.persistence")
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
