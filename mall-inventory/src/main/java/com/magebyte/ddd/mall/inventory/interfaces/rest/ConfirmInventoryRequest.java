package com.magebyte.ddd.mall.inventory.interfaces.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认出库请求体：把已预占的库存真正发走。
 *
 * <p>字段形状与预占/释放请求保持一致，三个方向共用同一种"SKU + 数量"的线协议，
 * 调用方不必为每个方向学一套报文。
 */
public record ConfirmInventoryRequest(
        @NotBlank(message = "skuCode 不能为空")
        String skuCode,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "出库数量必须 ≥ 1")
        Integer quantity) {
}
