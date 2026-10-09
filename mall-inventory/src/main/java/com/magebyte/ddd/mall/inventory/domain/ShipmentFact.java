package com.magebyte.ddd.mall.inventory.domain;
import java.util.*;
/** 消费边界翻译后的发货事实；不依赖订单 Java 类或消息框架。 */
public record ShipmentFact(String eventId, String orderNo, List<Line> lines, String fingerprint) {
    public record Line(String reservationNo, String skuCode, int quantity) {}
    public ShipmentFact {
        if (eventId == null || eventId.isBlank() || orderNo == null || orderNo.isBlank()
                || fingerprint == null || fingerprint.isBlank() || lines == null || lines.isEmpty())
            throw new InventoryDomainException("发货事实缺少身份或明细");
        lines = List.copyOf(lines);
        Set<String> identities = new HashSet<>();
        for (Line line : lines) {
            if (line == null || line.quantity() <= 0 || line.skuCode() == null || line.skuCode().isBlank()
                    || !Objects.equals(line.reservationNo(), orderNo + ":" + line.skuCode())
                    || !identities.add(line.reservationNo()))
                throw new InventoryDomainException("发货明细身份、数量或唯一性不合法");
        }
    }
}
