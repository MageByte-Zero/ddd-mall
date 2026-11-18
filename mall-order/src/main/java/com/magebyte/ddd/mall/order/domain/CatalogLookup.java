package com.magebyte.ddd.mall.order.domain;

import java.math.BigDecimal;
import java.util.Optional;

/** 订单只读取创建订单所需的目录快照。 */
public interface CatalogLookup {
    record Sku(Long skuId, Long productId, String name, BigDecimal unitPrice, Boolean enabled) {}
    Optional<Sku> find(long skuId);
}
