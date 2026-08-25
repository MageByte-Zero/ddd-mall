package com.magebyte.ddd.mall.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 库存限界上下文（支撑域）启动类。
 *
 * <p>端口 8082；注册到 Nacos 的服务名为 mall-inventory。
 * 不声明 @MapperScan：第 12 讲 InventoryMapper 落地时再扫描 infrastructure.persistence。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
