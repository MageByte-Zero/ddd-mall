package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 接口层统一异常翻译：把领域异常翻译成 HTTP 响应。
 *
 * <p>领域层只抛 {@link OrderDomainException}，不知道 HTTP 的存在；
 * 协议适配是接口层的职责。非法状态迁移、金额不符这类"请求语义不成立"
 * 的业务拒绝，按需求文档验收条件翻译为 422（Unprocessable Entity）——
 * 它不是 400（报文格式没坏），也不是 500（服务器没出错），
 * 是"听懂了请求，但业务规则不允许"。
 *
 * <p>REST 控制器第 11 讲进场后，本 advice 对所有控制器自动生效。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderDomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomainException(OrderDomainException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of(
                        "code", HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        "message", e.getMessage()));
    }
}
