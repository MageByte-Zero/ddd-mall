package com.magebyte.ddd.mall.order.application;

/** 金额由订单行的成交快照计算。 */
public record PartialRefundCommand(String orderNo, String refundNo, String lineId, int quantity, String reason) {}
