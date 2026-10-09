package com.magebyte.ddd.mall.order.interfaces.rest;
import jakarta.validation.constraints.NotBlank;
public record ShipOrderRequest(@NotBlank String operatedBy) {}
