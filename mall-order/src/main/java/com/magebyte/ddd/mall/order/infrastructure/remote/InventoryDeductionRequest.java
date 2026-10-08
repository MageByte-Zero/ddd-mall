package com.magebyte.ddd.mall.order.infrastructure.remote;

/** 库存预占线协议；requestKey 与预占单号分别标识请求和业务归属。 */
public record InventoryDeductionRequest(String requestKey, String reservationNo, String skuCode, int quantity) {
}
