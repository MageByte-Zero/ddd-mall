-- 订单表（订单聚合根）
CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL COMMENT '订单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    status VARCHAR(32) NOT NULL COMMENT '订单状态',
    total_amount DECIMAL(18,2) NOT NULL COMMENT '订单总金额',
    paid_amount DECIMAL(18,2) DEFAULT NULL COMMENT '实付金额',
    receiver_name VARCHAR(64) DEFAULT NULL COMMENT '收货人',
    receiver_phone VARCHAR(32) DEFAULT NULL COMMENT '收货电话',
    receiver_address VARCHAR(512) DEFAULT NULL COMMENT '收货地址',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单';

-- 订单项（订单聚合内部实体）
CREATE TABLE IF NOT EXISTS t_order_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL COMMENT '订单ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    sku_id BIGINT NOT NULL COMMENT 'SKU ID',
    product_name VARCHAR(256) NOT NULL COMMENT '商品名称(冗余)',
    quantity INT NOT NULL COMMENT '数量',
    unit_price DECIMAL(18,2) NOT NULL COMMENT '单价',
    subtotal DECIMAL(18,2) NOT NULL COMMENT '小计',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单项';

-- 订单状态历史（订单聚合内部值对象）
CREATE TABLE IF NOT EXISTS t_order_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(32) DEFAULT NULL,
    to_status VARCHAR(32) NOT NULL,
    reason VARCHAR(256) DEFAULT NULL,
    operated_by VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单状态历史';

-- Outbox 事件表（用于第 9 讲）
CREATE TABLE IF NOT EXISTS t_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL COMMENT '事件唯一ID',
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Outbox 事件';

-- Seata undo_log（Seata AT 模式自动管理，第 10 讲）
CREATE TABLE IF NOT EXISTS undo_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    branch_id BIGINT NOT NULL,
    xid VARCHAR(100) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created DATETIME NOT NULL,
    log_modified DATETIME NOT NULL,
    ext VARCHAR(100) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata AT undo_log';
