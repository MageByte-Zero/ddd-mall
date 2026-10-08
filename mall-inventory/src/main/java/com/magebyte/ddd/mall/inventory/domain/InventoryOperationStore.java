package com.magebyte.ddd.mall.inventory.domain;

import com.magebyte.ddd.mall.inventory.domain.Inventory;
import com.magebyte.ddd.mall.inventory.domain.Reservation;
import java.util.Optional;

/** 应用层所需的持久化端口；SQL 与 Seata 适配留在基础设施。 */
public interface InventoryOperationStore {
    Inventory lockInventory(String skuCode);
    Optional<InventoryOperationResult> findRequest(String requestKey);
    Optional<InventoryOperationResult> findAction(String reservationNo, String action);
    Optional<Reservation> findReservation(String reservationNo);
    void saveReservation(Reservation reservation, boolean insert);
    void appendLog(InventoryOperation request, String action, com.magebyte.ddd.mall.inventory.domain.StockSnapshot before, com.magebyte.ddd.mall.inventory.domain.StockSnapshot after);
    void saveResult(InventoryOperationResult result);
}
