package com.magebyte.ddd.mall.order.application;

/**
 * 创建订单用例的入参（应用层命令对象）。
 *
 * <p>为什么不用裸参数列表：第 10 讲的 {@code createOrder} 有 7 个入参，
 * 本讲补上收货地址后变到 10 个，调用方要靠"第几个参数是单价"来读代码，
 * 参数顺序一旦写错（两个 String、三个 Long 相邻）编译器还拦不住。
 * 命令对象把"一次用例调用"变成一个具名整体，字段顺序不再重要。
 *
 * <p>为什么不放接口层：请求 DTO（{@code CreateOrderRequest}）是 HTTP 协议的形状，
 * 带 {@code jakarta.validation} 注解；命令对象是应用层的形状，两者之间隔着
 * "协议 ↔ 应用层"的翻译。今天它们字段几乎一样，明天接 MQ 消费或内部 RPC 时
 * 就会分叉——提前分开比事后重构便宜。
 */
public record CreateOrderCommand(
        Long userId,
        Long productId,
        Long skuId,
        String productName,
        int quantity,
        String unitPrice,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        boolean simulateRollbackFailure) {

    /**
     * 无故障注入的构造入口：测试和调用方不必关心教学开关。
     */
    public static CreateOrderCommand of(Long userId, Long productId, Long skuId,
                                        String productName, int quantity, String unitPrice,
                                        String receiverName, String receiverPhone,
                                        String receiverAddress) {
        return new CreateOrderCommand(userId, productId, skuId, productName, quantity,
                unitPrice, receiverName, receiverPhone, receiverAddress, false);
    }
}
