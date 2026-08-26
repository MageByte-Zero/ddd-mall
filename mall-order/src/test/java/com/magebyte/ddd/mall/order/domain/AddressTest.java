package com.magebyte.ddd.mall.order.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Address 值对象：三字段成整体，空白与超长在构造时被拒。
 */
class AddressTest {

    @Test
    void value_equality() {
        assertEquals(new Address("张三", "13800138000", "南山区科技园"),
                new Address("张三", "13800138000", "南山区科技园"));
    }

    @Test
    void rejects_blank_fields() {
        assertThrows(OrderDomainException.class,
                () -> new Address(" ", "13800138000", "南山区科技园"));
        assertThrows(OrderDomainException.class,
                () -> new Address("张三", null, "南山区科技园"));
        assertThrows(OrderDomainException.class,
                () -> new Address("张三", "13800138000", ""));
    }

    @Test
    void rejects_oversized_fields() {
        OrderDomainException e = assertThrows(OrderDomainException.class,
                () -> new Address("张".repeat(65), "13800138000", "南山区科技园"));
        assertTrue(e.getMessage().contains("64"));
    }
}
