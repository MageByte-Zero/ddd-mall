package com.magebyte.ddd.mall.order.infrastructure.remote;

import feign.RequestInterceptor;
import org.apache.seata.core.context.RootContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 的 Seata XID 传播配置。
 *
 * <p>跨服务事务传播的本质是把全局事务 XID 传给下游并绑定到对方的运行时
 * （官方文档"微服务框架支持"：Dubbo 用 attachment，HTTP 用请求头）。
 * Seata 的 Spring MVC 拦截器（{@code JakartaSeataWebMvcConfigurer}，starter 自动装配）
 * 在服务提供方读取 {@code TX_XID} 请求头并绑定 XID；这里在消费方用 Feign
 * 拦截器把当前线程的 XID 写进同一个头，传播链路即打通。
 */
@Configuration
public class SeataFeignConfig {

    /** Seata HTTP 传播头名：与服务端 {@code TransactionPropagationInterceptor} 读取的头一致。 */
    private static final String TX_XID_HEADER = "TX_XID";

    @Bean
    public RequestInterceptor seataXidPropagationInterceptor() {
        return template -> {
            String xid = RootContext.getXID();
            if (xid != null) {
                template.header(TX_XID_HEADER, xid);
            }
        };
    }
}
