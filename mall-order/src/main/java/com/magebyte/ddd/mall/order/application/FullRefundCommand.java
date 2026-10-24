package com.magebyte.ddd.mall.order.application;
import com.magebyte.ddd.mall.order.domain.Money;
public record FullRefundCommand(String orderNo, String refundNo, Money amount, String reason, String operatedBy) {}
