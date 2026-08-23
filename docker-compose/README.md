# DDD 教学项目中间件

## 一键启动

```bash
cd /Users/magebte/Documents/magebyte/pay-columns/DDD/projects/ddd-mall/docker-compose
docker compose up -d
```

## 端口清单

| 服务 | 端口 | 控制台 |
|---|---|---|
| MySQL | 3306 | 命令行：`mysql -h localhost -uroot -pddd_root_2026` |
| Nacos | 8848, 9848 | http://localhost:8848/nacos (nacos/nacos) |
| RocketMQ NameSrv | 9876 | — |
| RocketMQ Broker | 10911, 10912 | — |
| RocketMQ Console | 8080 | http://localhost:8080 |
| Seata | 7091 (HTTP), 8091 (Server) | — |
| Sentinel Dashboard | 8858 | http://localhost:8858 (sentinel/sentinel) |
| Zipkin | 9411 | http://localhost:9411 |

## 停止

```bash
docker compose down        # 保留数据
docker compose down -v     # 同时删除 volumes（清空所有数据）
```

## 健康检查

```bash
docker compose ps
```

全部 services 状态应为 `Up` 或 `Up (healthy)`。

## 初始化 4 BC 数据库

```bash
docker exec -i ddd-mysql mysql -uroot -pddd_root_2026 <<EOF
CREATE DATABASE IF NOT EXISTS mall_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_inventory DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_payment DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
EOF
```
