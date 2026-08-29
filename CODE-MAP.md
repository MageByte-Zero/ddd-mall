# DDD 教学项目代码地图（CODE-MAP）

> **代码层单一来源**。paid-column-writer 每讲 pre-write 必读，post-write 必更。
> 与 D0（业务）、D5（ADR）共同构成跨讲一致性的三套单一来源。

## 维护规则

1. **pre-write 必读**：本讲写作前先读此文件，知道 4 BC 当前公共 API 表面，避免引用已废弃符号。
2. **post-write 必更**：本讲新增/修改/废弃的代码符号必须在本文件留痕（带讲次编号）。
3. **mvn compile 必过**：本讲 commit 前必须 `mvn clean compile -q` 0 错误；不通过禁止 commit。
4. **API 改名/废弃**：先在本文件加 `⚠ deprecated at L-N`，下讲方可真正删除（给读者一讲缓冲）。
5. **Flyway 版本对齐**：每张表的 schema 版本号必须与本文件 `### Schema` 段一致。
6. **每讲打 lesson tag**：本讲最后一个 commit 打 `lesson-NN` 注解标签并 push，作为读者对照文章的不可变快照；main 分支保持可直接编写提交，不开讲次特性分支。

## 命名约定（全 4 BC 统一，L6 锁定）

领域层核心概念**故意不加 `Entity`/`VO`/`Aggregate` 等模式后缀**——类名直接使用统一语言中的名词（Evans/Vernon 主流做法），避免污染业务语言。识别靠下面三个信号，不靠后缀：

**信号 1（最强）：包。** `domain.*` 里的是领域对象；`infrastructure.persistence.*DO` 是持久化对象；`interfaces.*` 是协议适配。

**信号 2：Java 类型关键字。**
- `record Xxx(...)` → 值对象（不可变、按值相等，编译器级标签，比任何 `XxxVO` 后缀都醒目）
- `enum Xxx`     → 通常是值对象（状态、类型等枚举）
- `class Xxx`（持有身份、有生命周期）→ 实体或聚合根

**信号 3：仓储归属 + 身份。** 拥有独立 `XxxRepository`、持有全局身份（订单号/业务 id）的那个实体 = 聚合根；没有自己的 Repository、只能经聚合根访问的 = 聚合内实体。

各层后缀约定：

| 层 | 类型 | 命名 | 后缀？ |
|---|---|---|---|
| domain | 聚合根/实体 | `Order`、`OrderItem` | 不加 |
| domain | 值对象 | `Money`、`Address`、`StatusChange`（用 record） | 不加 |
| domain | 仓储接口 | `OrderRepository` | **加 Repository** |
| domain | 领域服务 | `OrderDomainService` | **加 DomainService**（与应用服务区分） |
| domain | 领域事件 | `OrderPaidEvent`（L8 起，record） | **加 Event** |
| domain | 工厂 | `OrderFactory`（真正需要时） | 加 Factory |
| domain | 异常 | `OrderDomainException` | 加 DomainException |
| infrastructure | 持久化对象 | `OrderDO` | **加 DO** |
| infrastructure | MyBatis 映射器 | `OrderMapper` | **加 Mapper** |
| infrastructure | 仓储实现 | `OrderRepositoryImpl` | **加 RepositoryImpl** |
| application | 应用服务 | `OrderApplicationService`（L11） | **加 ApplicationService** |
| interfaces | Controller | `OrderController`（L11） | **加 Controller** |
| interfaces | 出入参 | `OrderRequest`/`OrderResponse` 或 `OrderDTO`（L11） | 按职责加 |
| commons(公共) | 统一响应体 | `Result<T>`（L7，mall-commons） | 不加（泛型容器） |

不引入 jMolecules 等构造型注解库，也不自定义 `@AggregateRoot`/`@ValueObject` 标记注解——保持领域层零依赖（含零注解依赖），识别交给上述三个信号 + ArchUnit 分层规则。

## 4 BC 公共 API

### mall-commons (公共模块) — 统一响应 L7
- `Result<T>`（`com.magebyte.ddd.mall.commons.response.Result`）— L7：统一响应体 code/message/data，`ok()` / `ok(T)` / `error(int, String)`；成功 code=200、失败沿用 HTTP 状态码语义（如 422）。业务无关、不依赖 Spring/Jackson，供各 BC 接口层复用
- `package-info.java` — L7 固化"只放业务无关公共代码"职责

### mall-order (核心域) — 启动类 L4 / 四层包 L5 / 领域代码 L6 / 状态机 L7

#### 四层包结构 — L5
- `interfaces/` `application/` `domain/` `infrastructure/` 四个包以 `package-info.java` 固化职责与依赖方向（编译产物，非空目录占位）
- `test/ArchitectureTest`（ArchUnit 1.4.1）— L5
  - 领域层不依赖外层三个包；领域层不依赖 `org.springframework..` / `com.baomidou..` / `org.apache.rocketmq..`（注解也算依赖）
  - 应用层不依赖接口层/基础设施层；基础设施层不依赖接口层/应用层
  - 骨架阶段 `allowEmptyShould(true)` 空转，L6 领域类落地后自动真查

#### domain/
- `Order` (聚合根) — L6，L7 扩状态机
  - `static Order create(Long userId, Address address)`
  - `void addItem(OrderItem item)`
  - `void markPaid(Money paidAmount, String operatedBy, LocalDateTime when)` — L7 签名加 operatedBy（L6 为 `markPaid(Money, LocalDateTime)`）
  - `void markShipped(String operatedBy, LocalDateTime when)` — L7：PAID → SHIPPED
  - `void confirmReceived(String operatedBy, LocalDateTime when)` — L7：SHIPPED → RECEIVED
  - `void cancel(String reason, String operatedBy, LocalDateTime when)` — L7 签名加 reason/operatedBy（L6 为 `cancel(LocalDateTime)`）；仅 PENDING_PAY → CANCELLED
  - `List<OrderItem> getItems()` (unmodifiable)
  - `List<StatusChange> statusHistory()` (unmodifiable) — L7
  - `static Order reconstitute(...)` — L7 签名加 `List<StatusChange> statusHistory`（插在 items 之后），重组即校验历史链（非空/首节 from 为空/首尾相接/每步合法/末节 to==当前状态）
  - 退款两条边（REFUND_REQUESTED/REFUNDED）迁移表已在 L6 就位，迁移方法待 L15
- `OrderItem` (实体) — L6
  - `static OrderItem create(Long productId, Long skuId, String productName, int quantity, Money unitPrice)`
  - `Money subtotal()`
- `Money` (值对象, record) — L6
  - `static Money of(BigDecimal)`, `static Money of(String)`, `static Money zero()`
  - `Money plus(Money)`, `Money multiply(int)`, `boolean isGreaterThan(Money)`
- `OrderStatus` (枚举) — L6 立迁移表，L7 注释更新
  - `boolean canTransitionTo(OrderStatus target)`（7 状态 8 合法迁移；终态 RECEIVED/CANCELLED/REFUNDED 无出边）
- `StatusChange` (值对象, record) — L7：`(OrderStatus from, OrderStatus to, String reason, String operatedBy, LocalDateTime occurredAt)`；from 仅创建记录为 null
- `Address` (值对象, record) — L6
- `OrderRepository` (仓储接口, 领域层定义、基础设施层实现) — L6
  - `Order save(Order)`, `Optional<Order> findById(Long)`, `Optional<Order> findByOrderNo(String)`
- `OrderDomainException` (领域异常) — L6；L7 起由接口层翻译为 HTTP 422

#### infrastructure/persistence/
- `OrderDO` / `OrderItemDO`（@TableName t_order / t_order_item；@Version / @TableLogic）— L6
- `OrderMapper extends BaseMapper<OrderDO>` — L6
- `OrderItemMapper extends BaseMapper<OrderItemDO>` — L6（订单项无独立仓储，随聚合根存取）
- `OrderStatusHistoryDO`（@TableName t_order_status_history；无 @Version/@TableLogic，只增不改）— L7
- `OrderStatusHistoryMapper extends BaseMapper<OrderStatusHistoryDO>` — L7（历史无独立仓储，随聚合根存取）
- `OrderRepositoryImpl implements OrderRepository`（@Repository；聚合↔DO 翻译）— L6，L7 扩历史：insert 全量写历史；update 按库中已存节数 delta 追加尾段；读出按 id 升序重组历史链

#### infrastructure/config/
- `MybatisPlusConfig`（OptimisticLockerInnerInterceptor，让 @Version 真生效）— L6

#### 启动类 — L6
- `OrderApplication` 补 `@MapperScan("com.magebyte.ddd.mall.order.infrastructure.persistence")`（第 4 讲预留的首个 Mapper 落地点）

#### application/（空，待 L11 引入 OrderApplicationService）

#### interfaces/rest/
- `GlobalExceptionHandler`（@RestControllerAdvice）— L7：`OrderDomainException` → HTTP 422（Unprocessable Entity），返回统一响应 `Result<Void>`（mall-commons）；控制器 L11 进场后自动生效

#### interfaces/
- （OrderController 待 L11）

### Schema (Flyway)
- `V1__init_order_schema.sql` — t_order / t_order_item / t_order_status_history / t_outbox_event / undo_log — L4

### mall-inventory (支撑域) — 启动类 L4 / 四层包 L5 / 领域代码待 L12

(待 L12 引入 Inventory 聚合根、PreDeductSkuService)

### Schema
- (空，待 L12)

### mall-payment (支撑域) — 启动类 L4 / 四层包 L5 / 领域代码待 L17

(待 L17 引入 Payment 聚合根、PaymentCallbackService)

### Schema
- (空，待 L17)

### mall-product (通用域) — 启动类 L4 / 四层包 L5 / 领域代码待 L18

(待 L18 引入 Product 聚合根、ProductQueryService)

### Schema
- (空，待 L18)

## 跨 BC 调用矩阵

(待 L17 引入 ACL 时填充。形如：mall-order → mall-payment via /api/pay, schema: OpenAPI in fixtures/contracts/order-to-payment.yaml)

## 每讲代码变更日志

| 讲次 | 新增 | 修改 | 废弃 |
|---|---|---|---|
| L0 | `docker-compose/init-scripts/01-create-databases.sql`（4 BC 库自动建）；Colima registry-mirrors 配置在本机 `~/.colima/default/colima.yaml`（不入库） | `docker-compose.yml`（移除过时 version；rocketmq-console 换 styletang 镜像；broker 加 user: root）、`rocketmq/broker.properties`（补 storePathRootDir/brokerIP1/namesrvAddr/listenPort）、`mall-order/application.yml`（L0/L4 禁用 Nacos config + Seata enabled=false） | `apacherocketmq/rocketmq-console-ng:2.0.1`（镜像已下架）。本讲工程基线变更落盘于 commit 856fac2/e5a6d13，文章 4386 字 status=done（2026-08-09） |
| L1 | 战略设计课，**无代码符号变更**。锁定领域事件词汇表（11 个，过去时，L8/L9/L14 落代码时以此为准，不得另起名）：OrderCreated、InventoryPreDeducted、PaymentRequested、OrderPaid、OrderCancelled、InventoryReleased、OrderShipped、OrderReceived、RefundRequested、RefundCompleted、InventoryRolledBack；锁定 4 BC 名 `mall-order`/`mall-inventory`/`mall-payment`/`mall-product`（L4 落 Maven 模块）；OrderPaid 归属 mall-order（由支付回调产生，跨 BC 紫色箭头） | — | —。文章 5830 字 status=done（2026-08-10） |
| L3 | 战略设计课，**无代码符号变更**。新增 `docs/adr/ADR-01-bounded-context-and-context-map.md`（Accepted），锁定 4 BC 职责卡、4×4 上下文映射矩阵（订单-库存/支付=C/S；订单-商品=Conformist+Separate Ways；库存/支付/商品两两=Separate Ways；库存/支付→订单=OHS/PL 领域事件）；商品 ID 不采用 Shared Kernel，用值对象复制；订单-库存暂为 C/S，L23 重审是否加 ACL（触发条件写入 ADR）。11 事件名与 L1 一致，未新增。`mvn clean compile -q` exit 0（4.1s） | — | —。文章 7761 字 status=done（2026-08-16） |
| L4 | `ProductApplication`/`InventoryApplication`/`PaymentApplication` 启动类（@EnableDiscoveryClient；**不声明 @MapperScan**——扫描路径不存在不报错但属预支未来，各模块在首个 Mapper 落地讲（6/12/17/18）再声明）、3 份 application.yml（discovery 开 / config 关 / flyway 关）、3 个模块 spring-boot-maven-plugin；落盘 commit 44e0da8 → a6d628a → 1f9be43 →（移除 @MapperScan 见本讲修订 commit）。4 应用注册 Nacos、mall-order Flyway V1 迁移实测通过 | — | — |
| L5 | 4 BC 四层包 `interfaces`/`application`/`domain`/`infrastructure`（16 个 `package-info.java`）；mall-order `ArchitectureTest`（ArchUnit 1.4.1 四条依赖方向禁令）；父 pom `archunit.version` + dependencyManagement；README 加「按讲阅读代码：lesson tag 快照」并修正进度清单；落盘 commit 9ad925c → 8f363ed →（docs commit）。`mvn test` 5 用例全绿；4 应用启动注册回归通过。边界样例：拼错包名的规则 `failed to check any classes`（ArchUnit 1.x 默认空规则即失败）；领域层挂 `@Component` 被规则二当场抓出 | — | — |
| L6 | `Order` / `OrderItem` / `Money` / `OrderStatus` / `Address` / `OrderRepository` / `OrderDomainException`；`OrderDO` / `OrderItemDO` / `OrderMapper` / `OrderItemMapper` / `OrderRepositoryImpl` / `MybatisPlusConfig`；`@MapperScan` 落地 | — | — |
| L7 | `StatusChange`（record 值对象）；`OrderStatusHistoryDO` / `OrderStatusHistoryMapper`；`interfaces/rest/GlobalExceptionHandler`（领域异常→422，返回统一响应 Result）；`Result<T>`（mall-commons，统一响应体） | `Order`：新增 `markShipped` / `confirmReceived` / `statusHistory()`，`markPaid`/`cancel`/`reconstitute` 签名扩参（操作人/原因/历史链），所有迁移经私有 `recordChange` 唯一入口落历史；`OrderRepositoryImpl`：历史随聚合同事务写入（insert 全量、update delta 追加）、读出按 id 升序重组；`OrderStatus` 注释更新；`interfaces/package-info.java` 依赖方向说明补充异常翻译 | — |
| L11 | `OrderApplicationService` / `OrderController` | `Order` 可能加公开方法 | — |
| ... | ... | ... | ... |

## 写入时机

每讲 post-write 末尾的"11 条门禁（8+3）"过完后，更新本文件：
- 在对应 BC 的章节下加本讲新增/修改的类与方法
- 在 `Schema` 段加本讲的 Flyway 文件
- 在 `每讲代码变更日志` 表里追加一行
- 若本讲废弃了某符号，在该符号处加 `⚠ deprecated at L-N`
