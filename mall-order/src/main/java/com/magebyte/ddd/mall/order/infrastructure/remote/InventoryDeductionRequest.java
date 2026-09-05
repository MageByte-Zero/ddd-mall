package com.magebyte.ddd.mall.order.infrastructure.remote;

/**
 * 库存扣减 HTTP 请求体（Feign 线协议对象，只住在基础设施层）。
 * 字段与库存 BC 的 DeductInventoryRequest 对齐。
 */
public record InventoryDeductionRequest(String skuCode, Integer quantity) {
}
