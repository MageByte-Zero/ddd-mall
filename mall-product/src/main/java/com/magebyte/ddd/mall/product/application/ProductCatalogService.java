package com.magebyte.ddd.mall.product.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCatalogService {
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");
    private final JdbcClient jdbc;

    public ProductCatalogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Sku(long skuId, long productId, String name, BigDecimal unitPrice, boolean enabled) {}

    @Transactional
    public Sku create(Long skuId, Long productId, String name, BigDecimal unitPrice, Boolean enabled) {
        if (skuId == null || skuId <= 0 || productId == null || productId <= 0) {
            throw new IllegalArgumentException("商品 ID 与 SKU ID 必须为正整数");
        }
        String normalizedName = validateName(name);
        BigDecimal price = validatePrice(unitPrice);
        boolean availability = validateAvailability(enabled);
        jdbc.sql("""
                INSERT INTO t_catalog_sku (sku_id, product_id, name, unit_price, enabled)
                VALUES (?, ?, ?, ?, ?)
                """).params(skuId, productId, normalizedName, price, availability).update();
        return new Sku(skuId, productId, normalizedName, price, availability);
    }

    @Transactional
    public Optional<Sku> update(long skuId, String name, BigDecimal unitPrice, Boolean enabled) {
        // A single statement changes the complete catalogue snapshot while holding the row lock.
        jdbc.sql("""
                UPDATE t_catalog_sku SET name = ?, unit_price = ?, enabled = ? WHERE sku_id = ?
                """).params(validateName(name), validatePrice(unitPrice), validateAvailability(enabled), skuId)
                .update();
        return find(skuId);
    }

    public Optional<Sku> find(long skuId) {
        return jdbc.sql("""
                SELECT sku_id, product_id, name, unit_price, enabled FROM t_catalog_sku WHERE sku_id = ?
                """).param(skuId).query((row, index) -> new Sku(row.getLong("sku_id"), row.getLong("product_id"),
                row.getString("name"), row.getBigDecimal("unit_price"), row.getBoolean("enabled"))).optional();
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 128) {
            throw new IllegalArgumentException("名称必须为 1 到 128 个字符");
        }
        return name.strip();
    }

    private static BigDecimal validatePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("CNY 单价必须为正、保留到分且不超过 9999999999.99 元");
        }
        return price.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean validateAvailability(Boolean enabled) {
        if (enabled == null) throw new IllegalArgumentException("必须提供 SKU 启用状态");
        return enabled;
    }
}
