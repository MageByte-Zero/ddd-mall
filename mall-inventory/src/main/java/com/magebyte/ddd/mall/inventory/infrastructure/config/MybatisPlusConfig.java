package com.magebyte.ddd.mall.inventory.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册乐观锁拦截器，让 {@code @Version} 真正生效。
 *
 * <p>不加这个拦截器，{@code @Version} 就是一个普通的整数列——MyBatis-Plus 不会自动
 * 把 {@code version = ?} 拼进 WHERE，也不会自增，写了等于没写。这是最容易漏的一步，
 * 第 12 讲的并发实验第一步就是验证它真的在拦。
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
