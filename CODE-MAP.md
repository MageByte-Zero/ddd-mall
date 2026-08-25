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

## 4 BC 公共 API

### mall-order (核心域) — 启动类 L4 / 四层包 L5 / 领域代码 L6

#### 四层包结构 — L5
- `interfaces/` `application/` `domain/` `infrastructure/` 四个包以 `package-info.java` 固化职责与依赖方向（编译产物，非空目录占位）
- `test/ArchitectureTest`（ArchUnit 1.4.1）— L5
  - 领域层不依赖外层三个包；领域层不依赖 `org.springframework..` / `com.baomidou..` / `org.apache.rocketmq..`（注解也算依赖）
  - 应用层不依赖接口层/基础设施层；基础设施层不依赖接口层/应用层
  - 骨架阶段 `allowEmptyShould(true)` 空转，L6 领域类落地后自动真查

#### domain/
- `Order` (聚合根) — L6
  - `static Order create(Long userId, Address address)`
  - `void addItem(OrderItem item)`
  - `void markPaid(Money paidAmount, LocalDateTime when)`
  - `void cancel(LocalDateTime when)`
  - `List<OrderItem> getItems()` (unmodifiable)
- `OrderItem` (实体) — L6
  - `static OrderItem create(Long productId, Long skuId, String productName, int quantity, Money unitPrice)`
  - `Money subtotal()`
- `Money` (值对象, record) — L6
  - `static Money of(BigDecimal)`, `static Money of(String)`, `static Money zero()`
  - `Money plus(Money)`, `Money multiply(int)`, `boolean isGreaterThan(Money)`
- `OrderStatus` (枚举) — L6
  - `boolean canTransitionTo(OrderStatus target)`
- `Address` (值对象, record) — L6

#### infrastructure/persistence/
- `OrderMapper extends BaseMapper<OrderDO>` — L6

#### application/（空，待 L11 引入 OrderApplicationService）

#### interfaces/（空，待 L11 引入 OrderController）

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
| L6 | `Order` / `OrderItem` / `Money` / `OrderStatus` / `Address` / `OrderMapper` | — | — |
| L11 | `OrderApplicationService` / `OrderController` | `Order` 可能加公开方法 | — |
| ... | ... | ... | ... |

## 写入时机

每讲 post-write 末尾的"11 条门禁（8+3）"过完后，更新本文件：
- 在对应 BC 的章节下加本讲新增/修改的类与方法
- 在 `Schema` 段加本讲的 Flyway 文件
- 在 `每讲代码变更日志` 表里追加一行
- 若本讲废弃了某符号，在该符号处加 `⚠ deprecated at L-N`
