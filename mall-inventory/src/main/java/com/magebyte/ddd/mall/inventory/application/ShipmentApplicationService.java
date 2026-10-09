package com.magebyte.ddd.mall.inventory.application;

import com.magebyte.ddd.mall.inventory.domain.ConsumedEventStore;
import com.magebyte.ddd.mall.inventory.domain.InventoryIdempotencyException;
import com.magebyte.ddd.mall.inventory.domain.InventoryOperation;
import com.magebyte.ddd.mall.inventory.domain.InventoryOperationStore;
import com.magebyte.ddd.mall.inventory.domain.ShipmentFact;
import org.apache.seata.spring.annotation.GlobalLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

/** 同一库存库内完成一条发货事实；协议翻译与 MQ 回调留在接口层。 */
@Service
public class ShipmentApplicationService {
    public static final String SUBSCRIPTION = "inventory-shipment-v2";

    private final ConsumedEventStore inbox;
    private final InventoryOperationStore operations;
    private final IdempotentInventoryService inventory;

    public ShipmentApplicationService(ConsumedEventStore inbox, InventoryOperationStore operations,
                                      IdempotentInventoryService inventory) {
        this.inbox = inbox;
        this.operations = operations;
        this.inventory = inventory;
    }

    @GlobalLock
    @Transactional
    public boolean process(ShipmentFact fact) {
        if (!inbox.insertIfAbsent(SUBSCRIPTION, fact.eventId(), fact.fingerprint())) {
            String previous = inbox.fingerprint(SUBSCRIPTION, fact.eventId()).orElseThrow();
            if (!previous.equals(fact.fingerprint())) {
                throw new InventoryIdempotencyException("同一 eventId 的发货内容发生变化");
            }
            return false;
        }
        // 所有库存锁按相同顺序获取，避免多 SKU 逆序导致死锁。
        var lines = fact.lines().stream()
                .sorted(Comparator.comparing(ShipmentFact.Line::skuCode)).toList();
        for (var line : lines) {
            operations.lockInventory(line.skuCode());
        }
        for (var line : lines) {
            inventory.confirm(new InventoryOperation("CONFIRM:" + line.reservationNo(),
                    line.reservationNo(), line.skuCode(), line.quantity()));
        }
        // Inbox、预占终态、动作回执、库存和流水在同一本地事务提交。
        return true;
    }
}
