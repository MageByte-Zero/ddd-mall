package com.magebyte.ddd.mall.inventory.interfaces.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 库存扣减请求体（跨 BC HTTP 契约，第 10 讲最小形态）。
 */
public record DeductInventoryRequest(
        @NotBlank(message = "skuCode 不能为空")
        String skuCode,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "扣减数量必须 ≥ 1")
        Integer quantity) {
}
