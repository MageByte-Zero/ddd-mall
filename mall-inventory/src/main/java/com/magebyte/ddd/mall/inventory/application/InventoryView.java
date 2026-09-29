package com.magebyte.ddd.mall.inventory.application;

/**
 * 库存读模型：用例出参。
 *
 * <p>不直接把 {@code Inventory} 聚合返回给接口层，理由和第 11 讲订单侧返回
 * {@code OrderDetail} 是一样的：聚合是对内的一致性边界，读模型是对外的视图。
 * 直接把聚合序列化出去，等于把"总库存 + 可售 + 已预占"这个内部结构钉死在 API 上，
 * 以后守恒式里多一个字段，所有调用方一起改。
 *
 * <p>三个数一起给，是因为<b>只给可售库存是不够的</b>——"可售 95"既可能是
 * "在库 100 被占 5"，也可能是"在库 95 没人占"，这两种状态对运营完全是两回事。
 */
public record InventoryView(
        String skuCode,
        String skuName,
        int totalStock,
        int availableStock,
        int reservedStock) {

    public static InventoryView from(com.magebyte.ddd.mall.inventory.domain.Inventory inventory) {
        return new InventoryView(
                inventory.skuCode(),
                inventory.skuName(),
                inventory.totalStock(),
                inventory.availableStock(),
                inventory.reservedStock());
    }
}
