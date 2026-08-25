package com.magebyte.ddd.mall.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 商品限界上下文（通用域）启动类。
 *
 * <p>端口 8081；注册到 Nacos 的服务名为 mall-product。
 * MapperScan 指向 infrastructure.persistence，第 18 讲落 Product 聚合根与 Mapper。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.magebyte.ddd.mall.product.infrastructure.persistence")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
