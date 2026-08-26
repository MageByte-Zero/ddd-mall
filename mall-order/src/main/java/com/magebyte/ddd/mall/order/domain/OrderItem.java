package com.magebyte.ddd.mall.order.domain;

import java.util.Objects;

/**
 * 订单项（OrderItem）实体。
 *
 * <p>有身份（id）、生命周期由订单聚合根管理：只能经由聚合根创建和访问，
 * 外部不允许绕过 Order 直接持有或修改订单项。创建后不可变——
 * 数量与单价在下单时冻结，改数量走的是"删项重加"而不是就地修改。
 *
 * <p>不变量：数量必须为正整数；单价由 {@link Money} 保证非负且两位小数；
 * 小计永远现算（单价 × 数量），不冗余存储第二份结果。
 */
public class OrderItem {

    private Long id;
    private final Long productId;
    private final Long skuId;
    private final String productName;
    private final int quantity;
    private final Money unitPrice;

    private OrderItem(Long id, Long productId, Long skuId,
                      String productName, int quantity, Money unitPrice) {
        this.id = id;
        this.productId = Objects.requireNonNull(productId, "商品ID不能为空");
        this.skuId = Objects.requireNonNull(skuId, "SKU ID不能为空");
        if (productName == null || productName.isBlank()) {
            throw new OrderDomainException("商品名称不能为空");
        }
        if (quantity <= 0) {
            throw new OrderDomainException("订单项数量必须为正整数: " + quantity);
        }
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = Objects.requireNonNull(unitPrice, "单价不能为空");
    }

    /** 新建订单项（尚未持久化，id 为空）。 */
    public static OrderItem create(Long productId, Long skuId, String productName,
                                   int quantity, Money unitPrice) {
        return new OrderItem(null, productId, skuId, productName, quantity, unitPrice);
    }

    /** 从持久化数据重组订单项，仓储实现专用入口。 */
    public static OrderItem reconstitute(Long id, Long productId, Long skuId,
                                         String productName, int quantity, Money unitPrice) {
        Objects.requireNonNull(id, "持久化订单项的 id 不能为空");
        return new OrderItem(id, productId, skuId, productName, quantity, unitPrice);
    }

    /** 小计 = 单价 × 数量，现算不存储。 */
    public Money subtotal() {
        return unitPrice.multiply(quantity);
    }

    public Long id() {
        return id;
    }

    public Long productId() {
        return productId;
    }

    public Long skuId() {
        return skuId;
    }

    public String productName() {
        return productName;
    }

    public int quantity() {
        return quantity;
    }

    public Money unitPrice() {
        return unitPrice;
    }

    /**
     * 实体的相等性由身份决定：已持久化的订单项按 id 判等；
     * 未持久化（id 为空）时退回引用相等。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderItem other)) {
            return false;
        }
        if (this.id == null || other.id == null) {
            return false;
        }
        return this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : id.hashCode();
    }
}
