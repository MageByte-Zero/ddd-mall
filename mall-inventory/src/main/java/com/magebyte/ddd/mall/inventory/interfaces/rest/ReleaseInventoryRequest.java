package com.magebyte.ddd.mall.inventory.interfaces.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 库存归还请求体（跨 BC HTTP 契约，第 11 讲随订单取消用例引入）。
 *
 * <p>字段形状与 {@link DeductInventoryRequest} 保持一致：两个端点是一个对称动作
 * 的两个方向，调用方（订单 BC）只需要一个 Feign 接口、两种语义。
 */
public record ReleaseInventoryRequest(
        @NotBlank(message = "skuCode 不能为空")
        String skuCode,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "归还数量必须 ≥ 1")
        Integer quantity) {
}
