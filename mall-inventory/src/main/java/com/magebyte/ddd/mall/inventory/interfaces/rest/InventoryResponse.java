package com.magebyte.ddd.mall.inventory.interfaces.rest;

/**
 * 库存查询响应体：总库存 / 可售 / 已预占 三个数一起返回。
 *
 * <p>三个数缺一不可。只给可售库存，调用方无法区分"在库 100 被占 5"和
 * "在库 95 没人占"——这两种状态对运营和风控完全是两回事。
 */
public record InventoryResponse(
        String skuCode,
        String skuName,
        int totalStock,
        int availableStock,
        int reservedStock) {

    public static InventoryResponse from(
            com.magebyte.ddd.mall.inventory.application.InventoryView view) {
        return new InventoryResponse(view.skuCode(), view.skuName(),
                view.totalStock(), view.availableStock(), view.reservedStock());
    }
}
