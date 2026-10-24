-- 第15讲：退款实体属于订单聚合，与订单共用乐观锁及本地事务。
-- 不猜测历史退款身份；历史已退款记录须先对账，本课程旧快照没有实际退款入口。
ALTER TABLE t_order
    ADD COLUMN refund_no VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL,
    ADD COLUMN refund_amount DECIMAL(18,2) DEFAULT NULL,
    ADD COLUMN refund_reason VARCHAR(200) DEFAULT NULL,
    ADD COLUMN refund_from_status VARCHAR(32) DEFAULT NULL,
    ADD COLUMN refund_outcome VARCHAR(32) DEFAULT NULL,
    ADD COLUMN refund_reference VARCHAR(128) DEFAULT NULL,
    ADD UNIQUE KEY uk_refund_no (refund_no);
