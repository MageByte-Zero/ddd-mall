package com.magebyte.ddd.mall.order.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 支付订单请求体：第三方支付成功后的回调参数形状（金额与流水身份）。
 *
 * <p>这里只做<b>格式</b>校验。"实付金额必须等于订单应付金额"是领域规则，
 * 由 {@code Order#markPaid} 判定并返回 422——校验注解管不了"这笔订单该收多少钱"。
 */
public record PayOrderRequest(
        @NotBlank(message = "paidAmount 不能为空")
        @Pattern(regexp = "^\\d{1,10}(\\.\\d{1,2})?$",
                message = "paidAmount 必须是金额，最多两位小数")
        String paidAmount,
        @NotBlank(message = "operatedBy 不能为空")
        String operatedBy) {
}
