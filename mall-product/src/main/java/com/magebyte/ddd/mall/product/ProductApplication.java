package com.magebyte.ddd.mall.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 商品限界上下文（通用域）启动类。
 *
 * <p>端口 8081；注册到 Nacos 的服务名为 mall-product。
 * 不声明 @MapperScan：第 18 讲 ProductMapper 落地时再扫描 infrastructure.persistence。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
