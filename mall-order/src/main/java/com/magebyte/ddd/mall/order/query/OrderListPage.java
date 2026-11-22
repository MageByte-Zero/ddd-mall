package com.magebyte.ddd.mall.order.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 订单列表的读模型，与可执行订单规则的领域对象分开。 */
public record OrderListPage(List<Item> orders, Long nextBeforeId, Instant inventoryObservedAt) {
    public record Item(String orderNo, LocalDateTime createdAt, String fulfillmentStatus, BigDecimal paidAmount,
                       RefundSummary refund, List<Line> lines, boolean hasMoreLines) {
        boolean shipmentRecorded() {
            return "SHIPPED".equals(fulfillmentStatus) || "RECEIVED".equals(fulfillmentStatus);
        }

        Item withLines(List<Line> observedLines) {
            return new Item(orderNo, createdAt, fulfillmentStatus, paidAmount, refund, observedLines, hasMoreLines);
        }
    }

    public record Line(String lineId, Long skuId, String productName, int quantity, BigDecimal unitPrice,
                       String reservationNo, ShipmentCheck shipmentCheck) {
        Line withShipmentCheck(ShipmentCheck check) {
            return new Line(lineId, skuId, productName, quantity, unitPrice, reservationNo, check);
        }
    }

    public record RefundSummary(RefundMode mode, String fullStatus, String fullOutcome, BigDecimal fullRequestedAmount,
                                BigDecimal inFlightAmount, BigDecimal refundedAmount) {}

    public enum RefundMode { NONE, FULL, PARTIAL }

    public enum ShipmentCheck {
        NOT_APPLICABLE, NOT_VERIFIED, NOT_FOUND, UNAVAILABLE, MISMATCH, RESERVED, CONFIRMED, RELEASED
    }
}
