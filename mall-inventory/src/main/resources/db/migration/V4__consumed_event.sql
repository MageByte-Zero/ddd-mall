CREATE TABLE t_consumed_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subscription VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_id VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_subscription_event(subscription,event_id)
) ENGINE=InnoDB;
