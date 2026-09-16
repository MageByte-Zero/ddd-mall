package com.magebyte.ddd.mall.order.infrastructure.remote;

/**
 * Feign 请求体：库存归还（对应库存 BC 的 {@code ReleaseInventoryRequest}）。
 *
 * <p>注意这是跨 BC 契约的实现细节副本，不是领域对象。两边字段名一致是刻意的——
 * 契约本身记在 {@code projects/ddd-mall/fixtures/contracts/order-to-inventory.yaml}，
 * 改契约先看那里。
 */
public record InventoryReleaseRequest(String skuCode, int quantity) {
}
