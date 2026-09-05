package com.magebyte.ddd.mall.inventory.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.inventory.domain.InventoryDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 库存接口层全局异常处理：库存领域异常翻译为 HTTP 422（Unprocessable Entity）。
 * 订单 BC 的 Feign 客户端收到 422 后按业务失败处理、触发全局事务回滚。
 */
@RestControllerAdvice
public class InventoryGlobalExceptionHandler {

    @ExceptionHandler(InventoryDomainException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Result<Void> handleDomainException(InventoryDomainException ex) {
        return Result.error(HttpStatus.UNPROCESSABLE_ENTITY.value(), ex.getMessage());
    }
}
