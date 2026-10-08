package com.magebyte.ddd.mall.inventory.domain;

import com.magebyte.ddd.mall.inventory.domain.InventoryDomainException;

public record InventoryOperation(String requestKey, String reservationNo, String skuCode, int quantity) {
    public InventoryOperation {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 128
                || reservationNo == null || reservationNo.isBlank() || reservationNo.length() > 128
                || skuCode == null || skuCode.isBlank() || skuCode.length() > 64 || quantity <= 0) {
            throw new InventoryDomainException("requestKey、reservationNo、SKU 和正数量均为必填");
        }
    }
}
