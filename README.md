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

### 第 19 讲支付防腐层

Java 实现位于 `mall-payment`。先读 `infrastructure/SimulatedV2Protocol` 如何翻译渠道结果，再沿着 `application/ObserveSimulatedPayment`、`application/PaymentCallbackService` 和 `domain/Payment` 找到查单、事务和支付规则。接口报文见 [V2 契约](fixtures/contracts/simulated-channel-v2.yaml)。

验证受理、超时、并发确认和 V1 升级时，以本仓库源码根目录为当前目录。需要 JDK 21、Maven、Docker Compose 和 Python 3。业务进程由夹具启动并退出，夹具还会提供可控的模拟查单端点。它使用独立的 MySQL 库和支付 topic，输出结果和日志位置。

```bash
docker compose -p ddd -f docker-compose/docker-compose.yml up -d mysql nacos rocketmq-namesrv rocketmq-broker seata-server
docker exec ddd-rocketmq-broker sh mqadmin updateTopic -n rocketmq-namesrv:9876 -c DefaultCluster -t order-events
mvn -B -DskipTests package

git clone --depth 1 --branch lesson-18 https://github.com/MageByte-Zero/ddd-mall.git ../ddd-mall-l18
(cd ../ddd-mall-l18 && mvn -B -pl mall-payment -am -DskipTests package)

python3 fixtures/verification/payment-channel-v2.py \
  --baseline-payment-jar ../ddd-mall-l18/mall-payment/target/mall-payment-0.1.0-SNAPSHOT.jar
```

输出中的 `beforeCapture` 记录受理和查单超时后数据库的状态，`captureRace` 记录两个入口交付同一收款结果后的确认与订单记录，`v1Upgrade` 记录原有支付单的兼容结果。

需要重跑第 17 讲的旧订单迁移、回调重放和投递故障时，在同一源码根目录执行下列命令。新版订单用第 18 讲的商品目录下单，原夹具的支付断言继续适用。

```bash
git clone --depth 1 --branch lesson-15 https://github.com/MageByte-Zero/ddd-mall.git ../ddd-mall-l15
(cd ../ddd-mall-l15 && mvn -B -pl mall-order -am -DskipTests package)

python3 fixtures/verification/payment-channel-v2.py \
  --lesson17-baseline-order-jar ../ddd-mall-l15/mall-order/target/mall-order-0.1.0-SNAPSHOT.jar
```

### 第 20 讲订单行与逐行预占

`POST /api/orders` 接受以下多行请求。单行调用仍可提交原顶层 `skuId` 和 `quantity`，并可附带原 `productId`、`productName`、`unitPrice`。两种请求形状分别使用。详情返回 `lineId`、`purchaseRequestNo` 和新行的预占引用。

```json
{
  "userId": 20,
  "receiverName": "采购员",
  "receiverPhone": "13800000000",
  "receiverAddress": "教学地址",
  "items": [
    {"skuId": 20101, "quantity": 1, "purchaseRequestNo": "PR-A"},
    {"skuId": 20101, "quantity": 2, "purchaseRequestNo": "PR-B"}
  ]
}
```

运行以下命令复验保存、库存不足回滚、取消、支付交接、发货重投及迁移。需要 JDK 21、Maven、Docker Compose 和 Python 3。已有运行中的 `ddd` Compose 容器可直接复用。夹具启停独立业务进程，保留独立测试数据库、topic 和日志以便核对。运行前确保本地端口 28081–28084 可用。命令从本仓库根目录执行。

```bash
docker compose -p ddd -f docker-compose/docker-compose.yml up -d mysql nacos rocketmq-namesrv rocketmq-broker seata-server
mvn -B -DskipTests package
git clone --depth 1 --branch lesson-19 https://github.com/MageByte-Zero/ddd-mall.git ../ddd-mall-l19
(cd ../ddd-mall-l19 && mvn -B -pl mall-order,mall-inventory -am -DskipTests package)
python3 fixtures/verification/order-lines.py --baseline-root ../ddd-mall-l19
```

夹具先用旧版应用创建真实订单和 v2 发货事件，再换新版应用复用测试数据库。结果中 `legacy` 记录旧预占及 v2 去重，`secondLineRollsBack` 记录整单失败前后的库存与副作用，`shipment` 可用来核对原两行和重投后的出库数量。升级实际存量时，先停止旧订单写入、升级库存消费者，再迁移订单库并启动新版订单写入。[订单行需求](fixtures/requirements/order-lines.md) · [发货事件契约](fixtures/contracts/order-events.yaml)

### 第 21 讲订单行部分退款

在 `mall-order` 使用 `RefundApplicationService.requestPartialRefund(PartialRefundCommand)` 登记申请，用 `recordPartialResult(orderNo, result)` 回记已翻译的结果。申请传订单号、退款申请号、订单行身份、数量和原因，成交金额由订单计算。本讲用 Java 集成测试驱动这两个入口。

从本仓库根目录执行。需要 JDK 21、Maven 和既有 DDD 中间件。创建独立 MySQL 练习库，把测试连接指向该库。下例使用 Compose 的 MySQL 服务及 root 账号，已有 `ddd` 容器可以直接复用。

```bash
docker compose -p ddd -f docker-compose/docker-compose.yml up -d mysql nacos rocketmq-namesrv rocketmq-broker
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/mall_order_l21?useSSL=false&allowPublicKeyRetrieval=true'
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD="$(docker compose -p ddd -f docker-compose/docker-compose.yml exec -T mysql sh -c 'printf %s "$MYSQL_ROOT_PASSWORD"')"

printf 'CREATE DATABASE mall_order_l21;\n' | docker compose -p ddd -f docker-compose/docker-compose.yml exec -T mysql \
  sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot'
docker compose -p ddd -f docker-compose/docker-compose.yml exec -T mysql \
  sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot mall_order_l21' \
  < mall-order/src/main/resources/db/migration/V1__init_order_schema.sql

mvn -B -pl mall-order -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=PartialRefundPersistenceTest,FullRefundPersistenceTest,FullRefundTest test
```

检查 `L21_OVER_LIMIT`、`L21_LEGAL_BOTH` 和 `L21_SAME_NUMBER`。它们分别记录超额处理、合法不同申请和同号重试。并发测试通过两条 MySQL 连接读取同一版本，再使用实际应用服务提交。若要跑全模块订单回归，先停止其他使用同一个订单日志消费组的测试应用。

验证 V4 到 V5 的升级时，按上面的方式另准备一个隔离空库，导入 V1 并把连接环境变量指向该库。使用下面的两个快照依次写入和重读，不在两个阶段之间清空或更换数据库。

```bash
git worktree add --detach ../ddd-mall-l20 lesson-20
upgrade_test=mall-order/src/test/java/com/magebyte/ddd/mall/order/application/PartialRefundUpgradeVerification.java
cp "$upgrade_test" "../ddd-mall-l20/$upgrade_test"
(cd ../ddd-mall-l20 && mvn -B -pl mall-order -am -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dtest=PartialRefundUpgradeVerification#seedV4' test)
mvn -B -pl mall-order -am -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dtest=PartialRefundUpgradeVerification#readV5' test
```

`seedV4` 先复现 ¥50 请求被旧整单入口拒绝，再写下两笔旧整单状态。看到 `L21_V5` 时，V5 已重读旧数据，并能通过原入口完成此前在途的整单申请。实际升级先停止旧版本写入，再运行迁移并启用新版。部分退款开始登记后，如需停收新申请，保留新版读取和回记在途申请的能力。[部分退款需求](fixtures/requirements/partial-refund.md) · [额度一致性决策](docs/adr/ADR-12-order-owned-refund-capacity.md)

### 第 22 讲已付款订单列表

可信内部调用用 `GET /internal/order-list?userId=22001&limit=20`。翻页把返回的 `nextBeforeId` 原样作为 `beforeId`，空值表示末页。列表每单预览前三行，`hasMoreLines` 表示可打开现有详情查看其余明细。用户筛选由查询实施，调用授权由内部接入方处理。

`inventoryObservedAt` 是库存批查成功时库存侧的观察时间。已发货但尚未确认出库时，原库存记录可能显示 `RESERVED`。找不到记录或调用超时会分别显示 `NOT_FOUND` 或 `UNAVAILABLE`。其余显示口径见列表需求和 OpenAPI 契约。

从仓库根目录运行。需 JDK 21、Maven、Python 3 及现有 DDD Compose 中间件。已有容器可以直接复用，过程夹具使用端口 29081–29084、独立数据库和消息主题。

```bash
docker compose -p ddd -f docker-compose/docker-compose.yml up -d mysql nacos rocketmq-namesrv rocketmq-broker seata-server
mvn -B -DskipTests package
python3 fixtures/verification/order-list.py
```

输出检查 `purchaseAndPayment`、`shipment`、`timeoutRecovery` 与 `missingReferences`。夹具通过暂停并恢复既有中继得到可控的先后窗口，库存超时通过只代理批查请求的本地 HTTP 连接验证。

退款及分页用 MySQL 上的 Java 集成测试复验。按第 21 讲的步骤另建练习库、导入订单 V1，并切换数据源环境变量后执行：

```bash
mvn -B -pl mall-order -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=OrderListPersistenceTest,ArchitectureTest test
```

这个测试覆盖两行多退款汇总、旧整单状态、同创建时间的稳定翻页、预览截断及 SQL 调用数。[业务口径](fixtures/requirements/order-list.md) · [查询契约](fixtures/contracts/order-list.yaml)

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
| `lesson-07` | 第 7 讲 | 订单状态机：发货/收货迁移方法 + 状态历史落库，领域异常翻译 HTTP 422 |

## 当前进度

项目随 DDD 实战课程逐步迭代：

- [x] 多模块 Maven 骨架 + 4 BC 独立 schema
- [x] 4 BC 四层包结构 + ArchUnit 依赖方向守护
- [x] 订单 BC：聚合根、值对象、不变量（第 6 讲）
- [x] 订单 BC：状态机迁移方法 + 状态历史落库 + 422 异常翻译（第 7 讲）
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
