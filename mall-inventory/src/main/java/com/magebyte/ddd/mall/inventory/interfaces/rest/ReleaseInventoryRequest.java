package com.magebyte.ddd.mall.inventory.interfaces.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 库存释放请求体（跨 BC HTTP 契约，第 11 讲随订单取消用例引入，
 * 第 12 讲改上界语义）。
 *
 * <p>字段形状与 {@link DeductInventoryRequest} 保持一致：两个端点是一个对称动作的
 * 两个方向，调用方（订单 BC）只需要一个 Feign 接口、两种语义。
 *
 * <p>第 11 讲的上界是"可售不得还到总库存之上"——两字段模型下唯一可用的近似。
 * 第 12 讲换成真正的上界：<b>释放量不得超过已预占量</b>。旧护栏拦不住
 * "订单 A 占 5 件、订单 B 占 5 件、总库存 100" 时把同一笔释放两次。
 */
public record ReleaseInventoryRequest(
        @NotBlank(message = "skuCode 不能为空")
        String skuCode,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "释放数量必须 ≥ 1")
        Integer quantity) {
}
