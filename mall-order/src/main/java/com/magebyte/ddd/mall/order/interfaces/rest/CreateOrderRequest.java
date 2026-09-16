package com.magebyte.ddd.mall.order.interfaces.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建订单请求体（第 11 讲补齐收货地址，形成完整 DTO 校验规约）。
 *
 * <p>校验注解全部是 Jakarta Bean Validation（JSR-380）：参数不满足时
 * Spring 在进控制器方法前就返回 400，领域层不必再多写一遍空判断。
 * 请求格式（这里）与领域规则（{@code Order} 聚合里）分开——
 * "手机号格式不对"是协议问题，"订单已支付不能取消"是业务问题，
 * 前者 400，后者 422。
 */
public record CreateOrderRequest(
        @NotNull(message = "userId 不能为空")
        Long userId,
        @NotNull(message = "productId 不能为空")
        Long productId,
        @NotNull(message = "skuId 不能为空")
        Long skuId,
        @NotBlank(message = "productName 不能为空")
        @Size(max = 128, message = "productName 不能超过 128 字符")
        String productName,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "购买数量必须 ≥ 1")
        Integer quantity,
        @NotBlank(message = "unitPrice 不能为空")
        @Pattern(regexp = "^\\d{1,10}(\\.\\d{1,2})?$",
                message = "unitPrice 必须是金额，最多两位小数")
        String unitPrice,
        @NotBlank(message = "receiverName 不能为空")
        @Size(max = 32, message = "收货人姓名不能超过 32 字符")
        String receiverName,
        @NotBlank(message = "receiverPhone 不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收货人手机号格式不正确")
        String receiverPhone,
        @NotBlank(message = "receiverAddress 不能为空")
        @Size(max = 200, message = "收货地址不能超过 200 字符")
        String receiverAddress,
        /** 教学开关：true 时扣库存成功后停留再失败，用于观察全局回滚。业务调用不要传。 */
        Boolean simulateRollbackFailure) {

    /**
     * 默认不触发故障注入（record 自带访问器返回 Boolean，判空用此方法）。
     */
    public boolean isSimulateRollbackFailure() {
        return Boolean.TRUE.equals(simulateRollbackFailure);
    }
}
