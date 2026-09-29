package com.magebyte.ddd.mall.inventory.interfaces.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 库存预占请求体（跨 BC HTTP 契约，第 10 讲建立，第 12 讲改语义）。
 *
 * <p>字段没变，语义变了：本端点现在做的是<b>预占</b>——把货从"能卖"挪到"被占住"，
 * 总库存不动。第 10、11 讲的"扣减"只减可售、总库存纹丝不动，守恒
 * （可售 + 已预占 = 总库存）一直是破的；改成预占后守恒成立，
 * 被占走的货有了落点，超时未支付才谈得上回收。
 *
 * <p>路径与字段名沿用第 10 讲契约，不在本讲做破坏性改名；
 * 第 13 讲引入幂等键时随请求体一并正名为 {@code /api/inventories/reservations}。
 */
public record DeductInventoryRequest(
        @NotBlank(message = "skuCode 不能为空")
        String skuCode,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "预占数量必须 ≥ 1")
        Integer quantity) {
}
