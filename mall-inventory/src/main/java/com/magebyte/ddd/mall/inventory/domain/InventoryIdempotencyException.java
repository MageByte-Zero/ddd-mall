package com.magebyte.ddd.mall.inventory.domain;

/** 身份被复用但请求意图不同；409 不代表可以原样重试。 */
public class InventoryIdempotencyException extends RuntimeException {
    public InventoryIdempotencyException(String message) { super(message); }
}
