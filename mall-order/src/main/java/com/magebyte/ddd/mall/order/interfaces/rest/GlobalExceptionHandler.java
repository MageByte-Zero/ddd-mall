package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import com.magebyte.ddd.mall.order.domain.OrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 接口层统一异常翻译：把领域异常翻译成统一响应 {@link Result}。
 *
 * <p>领域层只抛 {@link OrderDomainException}，不知道 HTTP 的存在；
 * 协议适配是接口层的职责。非法状态迁移、金额不符这类"请求语义不成立"
 * 的业务拒绝，按需求文档验收条件翻译为 422（Unprocessable Entity）——
 * 它不是 400（报文格式没坏），也不是 500（服务器没出错），
 * 是"听懂了请求，但业务规则不允许"。
 *
 * <p>响应体统一走公共模块 mall-commons 的 {@link Result}（code/message/data），
 * 与各 BC 成功响应同构：成功 code=200，失败 code 沿用 HTTP 状态码语义。
 * REST 控制器第 11 讲进场后，本 advice 对所有控制器自动生效。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderDomainException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Result<Void> handleDomainException(OrderDomainException e) {
        return Result.error(HttpStatus.UNPROCESSABLE_ENTITY.value(), e.getMessage());
    }

    /**
     * 找不到资源与"业务不允许"是两回事：前者 404，后者 422。
     * 分开之后调用方只用看状态码就能区分"我传错订单号"和"这笔订单不能这么操作"。
     */
    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFoundException(OrderNotFoundException e) {
        return Result.error(HttpStatus.NOT_FOUND.value(), e.getMessage());
    }
}
