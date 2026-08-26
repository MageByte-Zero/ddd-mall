package com.magebyte.ddd.mall.order.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Money 值对象：不可变、值相等、构造即校验。
 */
class MoneyTest {

    @Test
    void normalizes_scale_to_two_decimal_places() {
        // 1.0 与 1.00 归一后是同一个金额——BigDecimal.equals 的 scale 陷阱被消掉
        assertEquals(Money.of("1.0"), Money.of("1.00"));
        assertEquals(new BigDecimal("99.90"), Money.of("99.9").amount());
    }

    @Test
    void rejects_negative_amount() {
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> Money.of("-0.01"));
        assertTrue(e.getMessage().contains("不能为负"));
    }

    @Test
    void rejects_more_than_two_decimal_places() {
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> Money.of("9.999"));
        assertTrue(e.getMessage().contains("两位小数"));
    }

    @Test
    void rejects_invalid_format() {
        assertThrows(OrderDomainException.class, () -> Money.of("九块九"));
    }

    @Test
    void plus_returns_new_object_without_mutation() {
        Money a = Money.of("10.00");
        Money b = Money.of("2.50");
        Money sum = a.plus(b);
        assertEquals(Money.of("12.50"), sum);
        assertEquals(Money.of("10.00"), a); // 值对象不可变
    }

    @Test
    void multiply_by_quantity() {
        assertEquals(Money.of("29.70"), Money.of("9.90").multiply(3));
        assertEquals(Money.zero(), Money.of("9.90").multiply(0));
        assertThrows(OrderDomainException.class, () -> Money.of("9.90").multiply(-1));
    }

    @Test
    void compares_by_value() {
        assertTrue(Money.of("10.00").isGreaterThan(Money.of("9.99")));
        assertFalse(Money.of("10.00").isGreaterThan(Money.of("10.00")));
    }

    @Test
    void zero_is_zero() {
        assertEquals(Money.of("0.00"), Money.zero());
    }
}
