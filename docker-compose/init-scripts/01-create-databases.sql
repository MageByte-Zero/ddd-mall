-- 4 BC 各一个独立 schema，由 Docker MySQL 首次启动时自动执行
-- 字符集 utf8mb4，排序 utf8mb4_unicode_ci（与 command 参数一致）
CREATE DATABASE IF NOT EXISTS mall_order     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_inventory DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_payment   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_product   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
