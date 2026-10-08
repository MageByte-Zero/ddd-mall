package com.magebyte.ddd.mall.inventory.infrastructure.persistence;

import com.magebyte.ddd.mall.inventory.domain.*;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class InventoryOperationStoreImpl implements InventoryOperationStore {
    private final InventoryOperationMapper mapper;
    public InventoryOperationStoreImpl(InventoryOperationMapper mapper) { this.mapper=mapper; }
    public Inventory lockInventory(String sku) {
        InventoryDO d=mapper.lockInventory(sku);
        if(d==null) throw new InventoryDomainException("库存记录不存在: " + sku);
        return Inventory.reconstitute(d.getId(),d.getSkuCode(),d.getSkuName(),d.getTotalStock(),
                d.getAvailableStock(),d.getReservedStock(),d.getVersion(),d.getCreatedAt(),d.getUpdatedAt());
    }
    public Optional<InventoryOperationResult> findRequest(String key) { return Optional.ofNullable(mapper.findRequest(key)); }
    public Optional<InventoryOperationResult> findAction(String no,String action) { return Optional.ofNullable(mapper.findAction(no,action)); }
    public Optional<Reservation> findReservation(String no) {
        var d=mapper.findReservation(no);
        if(d==null) return Optional.empty();
        return Optional.of(new Reservation((String)d.get("reservation_no"),(String)d.get("sku_code"),
                ((Number)d.get("quantity")).intValue(),Reservation.State.valueOf((String)d.get("state"))));
    }
    public void saveReservation(Reservation r,boolean insert) {
        if(insert) mapper.insertReservation(r.reservationNo(),r.skuCode(),r.quantity(),r.state().name());
        else mapper.updateReservation(r.reservationNo(),r.state().name());
    }
    public void appendLog(InventoryOperation r,String action,StockSnapshot before,StockSnapshot after) {
        mapper.insertLog(r.skuCode(),action,r.quantity(),before.totalStock(),after.totalStock(),
                before.availableStock(),after.availableStock(),before.reservedStock(),after.reservedStock(),r.reservationNo(),r.requestKey());
    }
    public void saveResult(InventoryOperationResult result) { mapper.insertResult(result); }
}
