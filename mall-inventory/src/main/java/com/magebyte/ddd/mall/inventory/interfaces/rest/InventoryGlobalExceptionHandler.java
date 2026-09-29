package com.magebyte.ddd.mall.inventory.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.inventory.domain.InventoryConcurrencyException;
import com.magebyte.ddd.mall.inventory.domain.InventoryDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 库存接口层全局异常处理：把两类失败翻译成两个不同的状态码。
 *
 * <ul>
 *   <li>422 Unprocessable Entity —— <b>业务不允许</b>：库存不足、数量非法、
 *       释放量超过已预占。重试一万次结果都一样。</li>
 *   <li>409 Conflict —— <b>时机不巧</b>：乐观锁版本冲突，你读到的数据已经过期，
 *       重新发起整个用例可能就成了。</li>
 * </ul>
 *
 * <p>这两个状态码必须分开。混成 422 的后果是调用方只能无脑重试，
 * 于是把"库存不足"也重试一万次；混成 409 的后果是调用方把"库存不足"当成
 * 可以重试的冲突，用户看到的是一个永远在转圈的下单按钮。
 */
@RestControllerAdvice
public class InventoryGlobalExceptionHandler {

    @ExceptionHandler(InventoryDomainException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Result<Void> handleDomainException(InventoryDomainException ex) {
        return Result.error(HttpStatus.UNPROCESSABLE_ENTITY.value(), ex.getMessage());
    }

    @ExceptionHandler(InventoryConcurrencyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConcurrencyException(InventoryConcurrencyException ex) {
        return Result.error(HttpStatus.CONFLICT.value(), ex.getMessage());
    }
}
