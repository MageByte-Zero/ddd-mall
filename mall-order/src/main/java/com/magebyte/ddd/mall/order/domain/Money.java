package com.magebyte.ddd.mall.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额（Money）值对象。
 *
 * <p>值对象三要素：无身份、不可变、值相等。用 record 实现，构造即冻结。
 * 三条领域规则在构造时强制执行：
 * <ul>
 *   <li>统一归一到两位小数——{@link BigDecimal#equals} 连 scale 一起比较，
 *       不归一的话 {@code 1.0} 和 {@code 1.00} 会被判成两个不同的金额；</li>
 *   <li>超过两位小数直接拒绝，不做静默舍入——金额不允许精度损失；</li>
 *   <li>负数直接拒绝——领域层自己守住边界，不等数据库约束兜底。</li>
 * </ul>
 */
public record Money(BigDecimal amount) {

    private static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "金额不能为空");
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new OrderDomainException("金额最多保留两位小数: " + amount.toPlainString());
        }
        if (amount.signum() < 0) {
            throw new OrderDomainException("金额不能为负: " + amount.toPlainString());
        }
        // 归一到两位小数，保证 equals 的值相等语义（1.0 == 1.00）
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public static Money of(String amount) {
        try {
            return new Money(new BigDecimal(amount));
        } catch (NumberFormatException e) {
            throw new OrderDomainException("金额格式非法: " + amount, e);
        }
    }

    public static Money zero() {
        return ZERO;
    }

    /** 返回新对象，不修改自身——值对象不可变。 */
    public Money plus(Money other) {
        Objects.requireNonNull(other, "加数金额不能为空");
        return new Money(this.amount.add(other.amount));
    }

    public Money multiply(int quantity) {
        if (quantity < 0) {
            throw new OrderDomainException("数量不能为负: " + quantity);
        }
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)));
    }

    public boolean isGreaterThan(Money other) {
        Objects.requireNonNull(other, "比较金额不能为空");
        return this.amount.compareTo(other.amount) > 0;
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
