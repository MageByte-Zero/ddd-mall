package com.magebyte.ddd.mall.inventory.application;

import com.magebyte.ddd.mall.inventory.domain.*;
import org.apache.seata.spring.annotation.GlobalLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotentInventoryService {
    private final InventoryOperationStore store;
    private final InventoryRepository inventories;
    public IdempotentInventoryService(InventoryOperationStore store, InventoryRepository inventories) {
        this.store=store; this.inventories=inventories;
    }

    @GlobalLock
    @Transactional
    public InventoryOperationResult reserve(InventoryOperation request) { return execute(request,"RESERVE"); }

    @GlobalLock
    @Transactional
    public InventoryOperationResult release(InventoryOperation request) { return execute(request,"RELEASE"); }

    @GlobalLock
    @Transactional
    public InventoryOperationResult confirm(InventoryOperation request) { return execute(request,"CONFIRM"); }

    private InventoryOperationResult execute(InventoryOperation request, String action) {
        // 必须先做当前锁定读；有 XID 或 @GlobalLock 时 Seata 检查全局锁。
        Inventory inventory = store.lockInventory(request.skuCode());
        var previous = store.findRequest(request.requestKey());
        if (previous.isPresent()) {
            InventoryOperationResult result = previous.get();
            requireSame(result, request, action);
            return result;
        }
        // 换 requestKey 不能成为另一笔释放；同一业务动作必须仍然唯一。
        var oldAction = store.findAction(request.reservationNo(), action);
        if (oldAction.isPresent()) {
            requireSame(oldAction.get(), request, action);
            throw new InventoryIdempotencyException("同一业务动作必须沿用原 requestKey");
        }
        var existing = store.findReservation(request.reservationNo());
        Reservation reservation;
        var before = inventory.snapshot();
        if (action.equals("RESERVE")) {
            if (existing.isPresent()) throw new InventoryIdempotencyException("预占单号已存在");
            reservation = new Reservation(request.reservationNo(), request.skuCode(), request.quantity(), Reservation.State.RESERVED);
            inventory.reserve(request.quantity());
        } else {
            reservation = existing.orElseThrow(() -> new InventoryDomainException("预占单不存在: " + request.reservationNo()));
            reservation.requireSame(request.skuCode(),request.quantity());
            reservation = reservation.finish(action.equals("RELEASE") ? Reservation.State.RELEASED : Reservation.State.CONFIRMED);
            if (action.equals("RELEASE")) inventory.release(request.quantity());
            else inventory.confirm(request.quantity());
        }
        if (inventories.save(inventory) != 1) throw new InventoryConcurrencyException("库存版本冲突，重试整个用例");
        store.saveReservation(reservation, existing.isEmpty());
        var after = inventory.snapshot();
        InventoryOperationResult result = new InventoryOperationResult(request.requestKey(),request.reservationNo(),action,
                request.skuCode(),request.quantity(),after.totalStock(),after.availableStock(),after.reservedStock());
        store.saveResult(result);
        store.appendLog(request,action,before,after);
        return result;
    }

    private static void requireSame(InventoryOperationResult result, InventoryOperation request, String action) {
        if (!result.reservationNo().equals(request.reservationNo()) || !result.action().equals(action)
                || !result.skuCode().equals(request.skuCode()) || result.quantity()!=request.quantity()) {
            throw new InventoryIdempotencyException("同一 requestKey 的业务身份、动作、SKU 或数量发生变化");
        }
    }
}
