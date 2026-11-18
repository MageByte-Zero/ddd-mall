CREATE TABLE t_catalog_sku (
    sku_id BIGINT NOT NULL PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    enabled BOOLEAN NOT NULL,
    CHECK (sku_id > 0 AND product_id > 0),
    CHECK (CHAR_LENGTH(TRIM(name)) > 0 AND unit_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
