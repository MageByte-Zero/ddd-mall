package com.magebyte.ddd.mall.inventory.domain;

/** 原操作结果快照，不是当前库存查询；重放逐字段返回原结果。 */
public record InventoryOperationResult(String requestKey, String reservationNo, String action,
                                       String skuCode, int quantity, int totalStock,
                                       int availableStock, int reservedStock) {
}
