package com.magebyte.ddd.mall.order.interfaces.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建订单请求体（第 10 讲最小形态：只覆盖"下单 + 扣库存"演示所需字段；
 * 完整的 DTO 校验与用例集在订单用例串联讲次补齐）。
 */
public record CreateOrderRequest(
        @NotNull(message = "userId 不能为空")
        Long userId,
        @NotNull(message = "productId 不能为空")
        Long productId,
        @NotNull(message = "skuId 不能为空")
        Long skuId,
        @NotBlank(message = "productName 不能为空")
        String productName,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "购买数量必须 ≥ 1")
        Integer quantity,
        @NotBlank(message = "unitPrice 不能为空")
        String unitPrice,
        /** 教学开关：true 时扣库存成功后停留再失败，用于观察全局回滚。业务调用不要传。 */
        Boolean simulateRollbackFailure) {

    /**
     * 默认不触发故障注入（record 自带访问器返回 Boolean，判空用此方法）。
     */
    public boolean isSimulateRollbackFailure() {
        return Boolean.TRUE.equals(simulateRollbackFailure);
    }
}
