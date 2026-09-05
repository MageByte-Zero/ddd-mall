package com.magebyte.ddd.mall.inventory.domain;

/**
 * 库存领域异常：库存不变量被违反时抛出（库存不足、非法扣减数量等）。
 * 由接口层翻译为 HTTP 422；在全局事务内抛出时同时触发 Seata 全局回滚。
 */
public class InventoryDomainException extends RuntimeException {

    public InventoryDomainException(String message) {
        super(message);
    }
}
