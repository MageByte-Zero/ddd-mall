package com.magebyte.ddd.mall.inventory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 库存限界上下文（支撑域）启动类。
 *
 * <p>端口 8082；注册到 Nacos 的服务名为 mall-inventory。
 * MapperScan 指向 infrastructure.persistence，第 12 讲落 Inventory 聚合根与预占/释放用例。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.magebyte.ddd.mall.inventory.infrastructure.persistence")
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
