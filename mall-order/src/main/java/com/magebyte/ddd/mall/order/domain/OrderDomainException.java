package com.magebyte.ddd.mall.order.domain;

/**
 * 订单领域异常。
 *
 * <p>聚合的不变量被破坏、状态迁移不合法时抛出。领域层自己定义异常类型，
 * 不借用也不依赖任何框架的异常体系——业务规则的失败语义属于业务，不属于技术栈。
 */
public class OrderDomainException extends RuntimeException {

    public OrderDomainException(String message) {
        super(message);
    }

    public OrderDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
