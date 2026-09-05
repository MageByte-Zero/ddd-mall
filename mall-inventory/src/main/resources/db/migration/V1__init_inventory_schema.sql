-- 库存表（库存聚合根，第 12 讲扩为预占/释放模型并加 t_inventory_log）
CREATE TABLE IF NOT EXISTS t_inventory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku_code VARCHAR(64) NOT NULL COMMENT 'SKU 编码（跨 BC 身份）',
    sku_name VARCHAR(128) NOT NULL COMMENT '商品名称(冗余)',
    total_stock INT NOT NULL COMMENT '总库存（已预占 + 可售 = 总库存）',
    available_stock INT NOT NULL COMMENT '可售库存',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（第 12 讲启用）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存';

-- 教学种子数据：100 件可售（INSERT IGNORE 依赖 uk_sku_code，重复执行不报错）
INSERT IGNORE INTO t_inventory (sku_code, sku_name, total_stock, available_stock)
VALUES ('SKU-1001', '示例商品', 100, 100);

-- Seata undo_log（AT 模式自动管理：一阶段写入、二阶段提交后异步删除/回滚后清理）
-- 字段与官方 script/client/at/db/mysql.sql 对齐（2.1.0 核验）
CREATE TABLE IF NOT EXISTS undo_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    rollback_info LONGBLOB NOT NULL COMMENT 'rollback info',
    log_status INT NOT NULL COMMENT '0:normal status,1:defense status',
    log_created DATETIME(6) NOT NULL COMMENT 'create datetime',
    log_modified DATETIME(6) NOT NULL COMMENT 'modify datetime',
    PRIMARY KEY (id),
    UNIQUE KEY uk_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata AT undo_log';
