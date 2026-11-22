ALTER TABLE t_order_item
    ADD COLUMN line_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN reservation_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN purchase_request_no VARCHAR(64);

UPDATE t_order_item SET line_id = CONCAT('legacy-', id) WHERE line_id IS NULL;

ALTER TABLE t_order_item
    MODIFY line_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uk_order_line (order_id, line_id);
