package com.magebyte.ddd.mall.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单限界上下文（核心域）启动类。
 *
 * <p>端口 8084；注册到 Nacos 的服务名为 mall-order。
 *
 * <p>@MapperScan 随第 6 讲首个 Mapper（OrderMapper）落地而声明，
 * 扫描路径指向真实存在的基础设施层持久化包——不预支未来的路径。
 *
 * <p>@EnableScheduling 随第 9 讲 Outbox 中继器（{@code OutboxEventRelay}）
 * 落地而声明：开启 Spring 定时任务检测，让中继器的 @Scheduled 轮询生效。
 *
 * <p>@EnableFeignClients 随第 10 讲跨 BC 调用（库存扣减 Feign 客户端）
 * 落地而声明：扫描基础设施层 remote 包下的 @FeignClient。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@EnableFeignClients(basePackages = "com.magebyte.ddd.mall.order.infrastructure.remote")
@MapperScan("com.magebyte.ddd.mall.order.infrastructure.persistence")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
