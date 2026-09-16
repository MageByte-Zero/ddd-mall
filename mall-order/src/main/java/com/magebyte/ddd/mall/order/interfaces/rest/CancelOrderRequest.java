package com.magebyte.ddd.mall.order.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消订单请求体。
 *
 * <p>{@code reason} 落进状态历史链，长度限制是为了防止调用方把整个异常栈塞进来——
 * 历史表是给人排查用的，不是给日志系统用的。
 */
public record CancelOrderRequest(
        @NotBlank(message = "取消原因不能为空")
        @Size(max = 200, message = "取消原因不能超过 200 字符")
        String reason,
        @NotBlank(message = "operatedBy 不能为空")
        String operatedBy,
        /** 教学开关：true 时库存归还成功后停留再失败，用于观察取消方向的全局回滚。 */
        Boolean simulateReleaseFailure) {

    public boolean isSimulateReleaseFailure() {
        return Boolean.TRUE.equals(simulateReleaseFailure);
    }
}
