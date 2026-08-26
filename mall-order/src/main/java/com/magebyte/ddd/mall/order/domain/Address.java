package com.magebyte.ddd.mall.order.domain;

/**
 * 收货地址（Address）值对象。
 *
 * <p>三个字段的属性集合，构成"收货地址"这一个完整概念：无身份、不可变、
 * 按值相等。字段长度上限与 t_order 表 receiver_* 三列对齐——领域层先于
 * 数据库拒绝超长输入，错误信息是业务语言而不是 SQL 异常。
 *
 * <p>持久化时不单独建表：值对象以属性嵌入的方式落在订单表里（见
 * V1__init_order_schema.sql 的 receiver_name / receiver_phone /
 * receiver_address 三列），保留概念完整性，又不增加表的复杂度。
 */
public record Address(String receiverName, String receiverPhone, String receiverAddress) {

    private static final int NAME_MAX = 64;
    private static final int PHONE_MAX = 32;
    private static final int ADDRESS_MAX = 512;

    public Address {
        receiverName = requireText(receiverName, "收货人", NAME_MAX);
        receiverPhone = requireText(receiverPhone, "收货电话", PHONE_MAX);
        receiverAddress = requireText(receiverAddress, "收货地址", ADDRESS_MAX);
    }

    private static String requireText(String value, String fieldLabel, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new OrderDomainException(fieldLabel + "不能为空");
        }
        if (value.length() > maxLength) {
            throw new OrderDomainException(fieldLabel + "长度不能超过 " + maxLength + " 个字符");
        }
        return value;
    }
}
