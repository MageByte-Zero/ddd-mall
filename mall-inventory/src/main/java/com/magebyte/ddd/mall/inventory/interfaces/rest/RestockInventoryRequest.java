package com.magebyte.ddd.mall.inventory.interfaces.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 补货入库请求体：采购到货、退货入库。
 *
 * <p>它是守恒式 {@code 可售 + 已预占 = 总库存} 里唯一让等号右边变大的入口，
 * 补货之后总库存与可售一起增加，已预占不动。
 */
public record RestockInventoryRequest(
        @NotBlank(message = "skuCode 不能为空")
        String skuCode,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "补货数量必须 ≥ 1")
        Integer quantity) {
}
