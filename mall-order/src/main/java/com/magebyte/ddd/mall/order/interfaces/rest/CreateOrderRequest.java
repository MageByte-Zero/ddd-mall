package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.order.application.CreateOrderCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建订单请求体（第 11 讲补齐收货地址，形成完整 DTO 校验规约）。
 *
 * <p>校验注解全部是 Jakarta Bean Validation（JSR-380）：参数不满足时
 * Spring 在进控制器方法前就返回 400，领域层不必再多写一遍空判断。
 * 请求格式（这里）与领域规则（{@code Order} 聚合里）分开——
 * "手机号格式不对"是协议问题，"订单已支付不能取消"是业务问题，
 * 前者 400，后者 422。
 * 商品 ID、名称和单价为兼容旧调用而保留。实际下单快照由商品目录提供。
 */
public record CreateOrderRequest(
        @NotNull(message = "userId 不能为空")
        Long userId,
        @Positive(message = "productId 必须为正整数")
        Long productId,
        @Positive(message = "skuId 必须为正整数")
        Long skuId,
        @Size(max = 128, message = "productName 不能超过 128 字符")
        String productName,
        @Min(value = 1, message = "购买数量必须 ≥ 1")
        Integer quantity,
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
        Boolean simulateRollbackFailure,
        @Size(min = 1, max = 50, message = "items 必须包含 1 至 50 行")
        List<@NotNull @Valid Item> items) {

    @AssertTrue(message = "请提交 items 或原单 SKU 字段，两种请求格式不可混用")
    public boolean isLineInputValid() {
        if (items == null) {
            return skuId != null && quantity != null;
        }
        return skuId == null && quantity == null && productId == null && productName == null && unitPrice == null;
    }

    public CreateOrderCommand toCommand() {
        if (items == null) {
            return new CreateOrderCommand(userId, productId, skuId, productName, quantity, unitPrice,
                    receiverName, receiverPhone, receiverAddress, Boolean.TRUE.equals(simulateRollbackFailure));
        }
        return new CreateOrderCommand(userId, items.stream().map(Item::toLine).toList(),
                receiverName, receiverPhone, receiverAddress, Boolean.TRUE.equals(simulateRollbackFailure));
    }

    public record Item(@NotNull @Positive Long skuId, @NotNull @Min(1) Integer quantity,
                       @NotBlank @Size(max = 64) String purchaseRequestNo) {
        private CreateOrderCommand.Line toLine() {
            return new CreateOrderCommand.Line(null, skuId, null, quantity, null, purchaseRequestNo);
        }
    }
}
