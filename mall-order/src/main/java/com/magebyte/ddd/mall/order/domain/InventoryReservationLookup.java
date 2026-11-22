package com.magebyte.ddd.mall.order.domain;

/** 核对历史订单推导出的候选预占仍属于相同 SKU、数量且处于预占状态。 */
public interface InventoryReservationLookup {
    void requireReserved(String reservationNo, String skuCode, int quantity);
}
