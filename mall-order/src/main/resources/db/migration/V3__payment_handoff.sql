ALTER TABLE t_order ADD COLUMN payment_managed TINYINT(1) NOT NULL DEFAULT 0;

CREATE TABLE t_payment_acceptance (
    payment_no VARCHAR(48) COLLATE utf8mb4_bin PRIMARY KEY,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_payment_acceptance_order (order_no, outcome)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_payment_inbox (
    event_id VARCHAR(48) COLLATE utf8mb4_bin PRIMARY KEY,
    payment_no VARCHAR(48) COLLATE utf8mb4_bin NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
