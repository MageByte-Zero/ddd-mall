-- L13: 身份采用二进制排序，区分大小写；不重写 L12 已有预占的历史归属。
CREATE TABLE t_inventory_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reservation_no VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    UNIQUE KEY uk_reservation_no(reservation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE t_inventory_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    reservation_no VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    action VARCHAR(16) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    total_stock INT NOT NULL,
    available_stock INT NOT NULL,
    reserved_stock INT NOT NULL,
    UNIQUE KEY uk_request_key(request_key),
    UNIQUE KEY uk_reservation_action(reservation_no,action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE t_inventory_log
    ADD COLUMN reservation_no VARCHAR(128) COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_key VARCHAR(128) COLLATE utf8mb4_bin NULL;
