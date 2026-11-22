package com.magebyte.ddd.mall.order.application;

import java.util.List;

/** 订单入口的统一命令。到达事务用例前，目录服务会核准每行成交快照。 */
public record CreateOrderCommand(Long userId, List<Line> lines,
                                 String receiverName, String receiverPhone, String receiverAddress,
                                 boolean simulateRollbackFailure) {
    public CreateOrderCommand {
        lines = List.copyOf(lines);
    }

    public CreateOrderCommand(Long userId, Long productId, Long skuId, String productName,
                              int quantity, String unitPrice, String receiverName, String receiverPhone,
                              String receiverAddress, boolean simulateRollbackFailure) {
        this(userId, List.of(new Line(productId, skuId, productName, quantity, unitPrice, null)),
                receiverName, receiverPhone, receiverAddress, simulateRollbackFailure);
    }

    public static CreateOrderCommand of(Long userId, Long productId, Long skuId,
                                        String productName, int quantity, String unitPrice,
                                        String receiverName, String receiverPhone, String receiverAddress) {
        return new CreateOrderCommand(userId, productId, skuId, productName, quantity,
                unitPrice, receiverName, receiverPhone, receiverAddress, false);
    }

    public record Line(Long productId, Long skuId, String productName, int quantity,
                       String unitPrice, String purchaseRequestNo) {}
}
