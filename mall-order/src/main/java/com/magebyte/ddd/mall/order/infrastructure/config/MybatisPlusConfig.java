package com.magebyte.ddd.mall.order.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>注册乐观锁插件：让 t_order 的 version 列与 OrderDO 上的 @Version
 * 真正生效——updateById 自动附加 {@code AND version = ?} 条件并把版本号 +1。
 * 没有这个插件，@Version 注解只是一个装饰。
 * 并发保护的业务含义在库存预占（第 12 讲）正式展开。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
