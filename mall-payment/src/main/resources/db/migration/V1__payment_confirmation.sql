CREATE TABLE t_payment (
    payment_no VARCHAR(48) COLLATE utf8mb4_bin PRIMARY KEY,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    channel VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    channel_txn_no VARCHAR(80) COLLATE utf8mb4_bin,
    confirmed_at DATETIME(6),
    UNIQUE KEY uk_payment_channel_txn (channel, channel_txn_no),
    CHECK (amount_minor > 0 AND currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_payment_outbox (
    payment_no VARCHAR(48) COLLATE utf8mb4_bin PRIMARY KEY,
    event_id VARCHAR(48) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(120),
    KEY idx_payment_delivery (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_payment_callback_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no VARCHAR(48) COLLATE utf8mb4_bin NOT NULL,
    claimed_order_no VARCHAR(64) NOT NULL,
    channel_txn_no VARCHAR(80),
    reported_amount_minor BIGINT NOT NULL,
    reported_currency VARCHAR(8) NOT NULL,
    reported_result VARCHAR(32) NOT NULL,
    reason VARCHAR(48) NOT NULL,
    observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_payment_review (payment_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
