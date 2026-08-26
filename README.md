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
├── mall-commons/      # 公共模块（当前为空，随讲次按需放入无业务公共代码）
├── mall-product/      # 商品 BC（通用域，port 8081）
├── mall-inventory/    # 库存 BC（支撑域，port 8082）
├── mall-payment/      # 支付 BC（支撑域，port 8083）
├── mall-order/        # 订单 BC（核心域，port 8084）
├── docker-compose/    # Nacos / Sentinel / Seata / RocketMQ / Zipkin 一键起
├── scripts/           # 中间件 + 四模块的启停脚本（见下方「脚本」）
├── fixtures/          # 脱敏需求、跨 BC 契约、失败样例
├── docs/adr/          # 架构决策记录
└── evals/             # 压测、契约测试脚本
```

四个业务模块内部统一采用 DDD 四层包结构（接口层 `interfaces` / 应用层 `application` / 领域层 `domain` / 基础设施层 `infrastructure`），层间依赖方向由 mall-order 的 ArchUnit 测试自动守护。

## 快速开始

### 前置依赖

- JDK 21（`/usr/libexec/java_home -v 21` 可用；脚本会自动探测并校验主版本）
- Docker runtime（Docker Desktop 或 Colima，`docker info` 可连接）
- Maven 3.8+（或直接用仓库自带的 `./mvnw`，会自动下载 Maven 3.9.9）

### 一条命令从零跑起来

```bash
git clone https://github.com/MageByte-Zero/ddd-mall.git
cd ddd-mall
./scripts/run.sh up          # 中间件 up → mvn package → 启动四个业务模块
```

约 2–3 分钟后（含首次镜像拉取和 Maven 下载），验证：

```bash
./scripts/run.sh status      # 容器状态 + 四模块 PID/端口 + Nacos 注册数
```

看到 `已注册服务数: 4 / 4` 即全部就绪。打开 Nacos 控制台 http://localhost:8848/nacos （nacos/nacos）可看到四个 mall-* 实例。

### 日常使用

```bash
# 只启动/停止业务模块（中间件保持运行）
./scripts/app.sh start all
./scripts/app.sh stop all

# 只启动核心域
./scripts/app.sh start order

# 查日志
./scripts/app.sh logs order

# 中间件管理
./scripts/infra.sh up        # 启动并等待 MySQL/Nacos 就绪
./scripts/infra.sh down      # 停止删除容器（保留数据卷）
./scripts/infra.sh status

# 环境自检（启动出问题先跑这个）
./scripts/healthcheck.sh

# 全停：业务模块 + 中间件
./scripts/run.sh down
# ⚠ 清空中间件数据（含 MySQL 全部 mall_* 库）：
./scripts/run.sh clean
```

启动后四模块端口：mall-product 8081 / mall-inventory 8082 / mall-payment 8083 / mall-order 8084。中间件端口与控制台见 `./scripts/infra.sh` 输出。

> 不想用脚本？中间件等价于 `cd docker-compose && docker compose up -d`；业务模块等价于 `./mvnw clean package -DskipTests` 后 `java -jar mall-<name>/target/mall-<name>-0.1.0-SNAPSHOT.jar`。脚本只是把这些命令串起来并加上 PID 管理、就绪探测和端口校验。

## 脚本一览

| 脚本 | 职责 |
|---|---|
| `scripts/run.sh` | 一键编排：中间件 + 四模块作为整体 up/down/status/clean |
| `scripts/infra.sh` | 中间件容器 up/down/stop/status/logs/clean |
| `scripts/app.sh` | 业务模块 build/start/stop/restart/status/logs |
| `scripts/healthcheck.sh` | JDK/Docker/端口/容器/Nacos/schema 逐项自检 |
| `scripts/common.sh` | 公共函数（被其他脚本 source，不直接执行） |

业务模块以 `nohup java -jar` 后台运行，PID 写入 `logs/mall-<m>.pid`，标准输出写入 `logs/mall-<m>.out`（`logs/` 已在 `.gitignore`）。

## 按讲阅读代码：lesson tag 快照

本仓库随课程**螺旋式演进**：`main` 分支永远是最新、可运行的完整状态；每一讲结束时的代码状态打一个 `lesson-NN` tag，作为不可变快照。对照某一讲的文章实践时，切到对应 tag：

```bash
git clone https://github.com/MageByte-Zero/ddd-mall.git
cd ddd-mall
git checkout lesson-05   # 切到第 5 讲结束时的代码快照
git checkout main        # 回到最新状态
```

| tag | 对应讲次 | 代码状态 |
|---|---|---|
| `lesson-04` | 第 4 讲 | 4 BC 骨架：启动类 + 配置 + mall-order Flyway V1，4 应用注册 Nacos |
| `lesson-05` | 第 5 讲 | 4 BC 四层包结构 + ArchUnit 依赖方向守护 |
| `lesson-06` | 第 6 讲 | 订单聚合根：实体+值对象+不变量，仓储依赖倒置落地 |

## 当前进度

项目随 DDD 实战课程逐步迭代：

- [x] 多模块 Maven 骨架 + 4 BC 独立 schema
- [x] 4 BC 四层包结构 + ArchUnit 依赖方向守护
- [x] 订单 BC：聚合根、值对象、不变量（第 7 讲补完整状态机）
- [ ] 库存 BC：乐观锁预占 / 释放
- [ ] 领域事件 + Outbox
- [ ] Seata AT 跨 BC 事务
- [ ] Saga 编排
- [ ] Spring Cloud Gateway 灰度
- [ ] Sentinel 限流
- [ ] 分布式链路追踪

## 架构决策

关键决策记录在 [`docs/adr/`](docs/adr/)，包括 BC 划分、聚合边界、一致性策略等。

## 许可证

[MIT](LICENSE) © MageByte-Zero
