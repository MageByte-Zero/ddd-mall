# ddd-mall

一个用 **Domain-Driven Design（领域驱动设计，DDD）** 战术模式真实落地的电商订单中心教学项目。四个限界上下文（Bounded Context，BC）独立代码、独立 schema，基于 Spring Cloud Alibaba 全家桶，从项目骨架一路演进到 Saga / Outbox / 灰度发布。

> 教学用例，不是生产脚手架。业务代码刻意保持小而真，方便读者对照 DDD 战术模式阅读。

## 技术栈

| 层 | 选型 | 版本 |
|---|---|---|
| JDK | OpenJDK | 21 LTS |
| Web | Spring Boot | 3.3.5 |
| 微服务 | Spring Cloud | 2023.0.3 |
| 全家桶 | Spring Cloud Alibaba | 2023.0.1.3 |
| ORM | MyBatis-Plus | 3.5.9 |
| 数据库 | MySQL | 8.x |
| DDL 迁移 | Flyway | 10.20.0 |
| 注册 / 配置 | Nacos | 2.x |
| 分布式事务 | Seata | 2.0.0 |
| 消息 | RocketMQ | 5.x |
| 网关 | Spring Cloud Gateway | 4.x |
| 流控 | Sentinel | 1.8.x |
| 链路 | Micrometer Tracing + Zipkin | 3.x |

## 模块布局

四个 BC 各自独立 Maven 模块、独立 MySQL schema：

```
ddd-mall/
├── mall-commons/      # 公共：BaseEntity、统一异常、工具类（无 DB）
├── mall-product/      # 商品 BC（通用域，port 8081）
├── mall-inventory/    # 库存 BC（支撑域，port 8082）
├── mall-payment/      # 支付 BC（支撑域，port 8083）
├── mall-order/        # 订单 BC（核心域，port 8084）
├── docker-compose/    # Nacos / Sentinel / Seata / RocketMQ / Zipkin 一键起
├── fixtures/          # 脱敏需求、跨 BC 契约、失败样例
├── docs/adr/          # 架构决策记录
└── evals/             # 压测、契约测试脚本
```

## 快速开始

### 前置依赖

- JDK 21（`/usr/libexec/java_home -v 21` 可用）
- Docker runtime（Docker Desktop 或 Colima）
- Maven 3.8+

### 1. 启动中间件

```bash
cd docker-compose
docker compose up -d
docker compose ps   # 8 个容器 Up（Seata 在 AT 讲次前可暂不启用）
```

### 2. 编译

```bash
# 必须用 JDK 21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean install
```

### 3. 启动核心域

```bash
cd mall-order
mvn spring-boot:run
# 启动后访问 http://localhost:8084/actuator/health
# Nacos 控制台：http://localhost:8848/nacos （nacos/nacos）
```

## 当前进度

项目随 DDD 实战课程逐步迭代：

- [x] 多模块 Maven 骨架 + 4 BC 独立 schema
- [x] 订单 BC：聚合根、值对象、状态机
- [x] 库存 BC：乐观锁预占 / 释放
- [x] 领域事件 + Outbox
- [ ] Seata AT 跨 BC 事务
- [ ] Saga 编排
- [ ] Spring Cloud Gateway 灰度
- [ ] Sentinel 限流
- [ ] 分布式链路追踪

## 架构决策

关键决策记录在 [`docs/adr/`](docs/adr/)，包括 BC 划分、聚合边界、一致性策略等。

## 许可证

[MIT](LICENSE) © MageByte-Zero
