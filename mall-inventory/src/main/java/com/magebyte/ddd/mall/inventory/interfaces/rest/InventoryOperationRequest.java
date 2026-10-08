package com.magebyte.ddd.mall.inventory.interfaces.rest;

import com.magebyte.ddd.mall.inventory.domain.InventoryOperation;
import jakarta.validation.constraints.*;

public record InventoryOperationRequest(@NotBlank @Size(max=128) String requestKey,
                                       @NotBlank @Size(max=128) String reservationNo,
                                       @NotBlank @Size(max=64) String skuCode,
                                       @Min(1) int quantity) {
    public InventoryOperation toCommand() { return new InventoryOperation(requestKey,reservationNo,skuCode,quantity); }
}
