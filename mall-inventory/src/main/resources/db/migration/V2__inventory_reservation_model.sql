-- 第 12 讲：库存从「总库存 + 可售」两字段升级为「总库存 + 可售 + 已预占」三字段守恒模型。
-- 守恒式（需求文档第 4 节第 2 条）：可售 + 已预占 = 总库存。

ALTER TABLE t_inventory
    ADD COLUMN reserved_stock INT NOT NULL DEFAULT 0 COMMENT '已预占库存（可售 + 已预占 = 总库存）'
    AFTER available_stock;

-- 历史数据校准：第 10、11 讲的 deduct 只减 available_stock、从不减 total_stock，
-- 于是库里出现过 total_stock=100、available_stock=93 这种"有 7 件既不在可售里、
-- 也没有任何字段记录它在哪"的状态。迁移到三字段后，守恒要求
-- available + reserved = total，必须给这 7 件一个落点。
--
-- 这里把它们解释为"仍被某个订单预占着"：这是唯一不丢信息的解释
-- （另一种做法是把 total 下调到等于 available，那等于承认这 7 件凭空消失）。
-- 真实项目里这一步要配合业务方核对预占单据，教学库直接迁移。
UPDATE t_inventory
SET reserved_stock = total_stock - available_stock
WHERE total_stock > available_stock;

-- 库存流水：每次变动的前后快照。它不是聚合的一部分，是审计需求。
-- 有了它，"库存少了 3 件"这种事故才能查到是哪一步挪的。
CREATE TABLE IF NOT EXISTS t_inventory_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku_code VARCHAR(64) NOT NULL COMMENT 'SKU 编码',
    change_type VARCHAR(32) NOT NULL COMMENT '变动类型：RESERVE/CONFIRM/RELEASE/RESTOCK',
    quantity INT NOT NULL COMMENT '变动数量（恒为正）',
    total_before INT NOT NULL COMMENT '变动前总库存',
    total_after INT NOT NULL COMMENT '变动后总库存',
    available_before INT NOT NULL COMMENT '变动前可售',
    available_after INT NOT NULL COMMENT '变动后可售',
    reserved_before INT NOT NULL COMMENT '变动前已预占',
    reserved_after INT NOT NULL COMMENT '变动后已预占',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sku_code (sku_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存流水';
