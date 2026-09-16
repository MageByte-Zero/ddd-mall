package com.magebyte.ddd.mall.order.domain;

/**
 * 订单不存在。
 *
 * <p>和 {@link OrderDomainException} 分开，原因在 HTTP 语义：
 * 领域异常表示"这笔业务规则不允许"，对应 422；订单不存在表示
 * "你要操作的资源我没有"，对应 404。混用一个类型，接口层就没法区分，
 * 调用方只能拿到 422 去猜是"订单有问题"还是"订单压根没有"。
 *
 * <p>注意它仍然是纯 Java 异常、零框架依赖——"翻译成哪个 HTTP 状态码"
 * 是接口层的判断，不是领域层的。
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
