CREATE TABLE t_order_partial_refund (
    order_id BIGINT NOT NULL,
    refund_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    line_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    amount DECIMAL(18,2) NOT NULL CHECK (amount >= 0),
    reason VARCHAR(200) NOT NULL,
    requested_from VARCHAR(24) NOT NULL,
    outcome VARCHAR(16),
    result_reference VARCHAR(128),
    PRIMARY KEY (order_id, refund_no),
    CONSTRAINT fk_partial_refund_order_line
        FOREIGN KEY (order_id, line_id) REFERENCES t_order_item (order_id, line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
