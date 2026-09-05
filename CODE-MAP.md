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
| application | 应用服务 | `OrderApplicationService`（L10 首个用例，L11 补全） | **加 ApplicationService** |
| interfaces | Controller | `OrderController`（L10 创建订单入口，L11 补全用例集） | **加 Controller** |
| interfaces | 出入参 | `CreateOrderRequest`/`CreateOrderResponse`（L10；record DTO，L11 起按用例扩展） | 按职责加 |
| commons(公共) | 统一响应体 | `Result<T>`（L7，mall-commons） | 不加（泛型容器） |

不引入 jMolecules 等构造型注解库，也不自定义 `@AggregateRoot`/`@ValueObject` 标记注解——保持领域层零依赖（含零注解依赖），识别交给上述三个信号 + ArchUnit 分层规则。

## 4 BC 公共 API

### mall-commons (公共模块) — 统一响应 L7
- `Result<T>`（`com.magebyte.ddd.mall.commons.response.Result`）— L7：统一响应体 code/message/data，`ok()` / `ok(T)` / `error(int, String)`；成功 code=200、失败沿用 HTTP 状态码语义（如 422）。业务无关、不依赖 Spring/Jackson，供各 BC 接口层复用
- `package-info.java` — L7 固化"只放业务无关公共代码"职责

### mall-order (核心域) — 启动类 L4 / 四层包 L5 / 领域代码 L6 / 状态机 L7 / 领域事件 L8 / Outbox L9 / 全局事务用例 L10

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
  - `List<DomainEvent> pullEvents()` — L8：取走聚合自上次保存以来产生的事件（不可变快照）并清空；reconstitute 重组的聚合事件列表永远为空（重组是还原历史，不产新事实）
  - `static Order reconstitute(...)` — L7 签名加 `List<StatusChange> statusHistory`（插在 items 之后），重组即校验历史链（非空/首节 from 为空/首尾相接/每步合法/末节 to==当前状态）
  - 事件挂载点：`create()` 抛 OrderCreatedEvent；私有 `recordChange()` 迁移成功后经 `raiseEventFor(target, reason, operatedBy, when)` 抛对应事件（非法迁移在 assertTransition 即抛出，不产事件）；退款两条边（REFUND_REQUESTED/REFUNDED）迁移表已在 L6 就位、迁移方法待 L15，raiseEventFor 对未接入状态显式抛错
  - 退款两条边（REFUND_REQUESTED/REFUNDED）迁移表已在 L6 就位，迁移方法待 L15

#### domain/（跨 BC 端口，L10）
- `InventoryDeductionPort`（纯 Java 端口接口）— L10：订单对"库存扣减"能力的出口抽象；`void deduct(String skuCode, int quantity)`；实现住基础设施层（依赖倒置），领域层不知道 Feign/HTTP。第 17/19 讲防腐层在适配器一侧加厚，本端口不动

#### domain/event/（领域事件子包，L8 增量引入）
- `DomainEvent`（纯 Java 接口，零框架依赖，ArchUnit 守）— L8：访问器 `eventId()`（UUID）/ `eventName()`（线上 wire name，= 消息 tag）/ `schemaVersion()`（当前全为 1）/ `orderNo()`（事件源业务身份；不用数据库自增 id——事件出生在入库前）/ `occurredOn()`
- 5 个 record 事件（事件名锁定 L1 词汇表过去时；均为 `implements DomainEvent`，各带 `NAME` / `SCHEMA_VERSION=1` 常量与 `static raise(...)` 工厂；`eventName`/`schemaVersion` 是 record 组件，消息体自包含类型与版本）：
  - `OrderCreatedEvent(eventId, eventName, schemaVersion, orderNo, userId, occurredOn)` — create() 抛出
  - `OrderPaidEvent(..., orderNo, paidAmount: Money, occurredOn)` — markPaid 抛出
  - `OrderShippedEvent(..., orderNo, operatedBy, occurredOn)` — markShipped 抛出
  - `OrderReceivedEvent(..., orderNo, operatedBy, occurredOn)` — confirmReceived 抛出
  - `OrderCancelledEvent(..., orderNo, reason, operatedBy, occurredOn)` — cancel 抛出
- `DomainEventPublisher`（发布端口，纯 Java 接口）— L8：`void publishAll(List<DomainEvent>)`；实现住基础设施层（依赖倒置），契约要求"事务提交后才发送、回滚整批丢弃"
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
- `OutboxEventDO`（@TableName t_outbox_event；状态常量 STATUS_PENDING/SENT/FAILED）— L9：Outbox 事件行；字段 eventId（=消息 keys）/aggregateType（"Order"）/aggregateId（=订单号）/eventType（=事件名/消息 tag）/payload（发往 MQ 的消息体 JSON）/status/retryCount/nextRetryAt/createdAt/sentAt；只住基础设施层
- `OutboxEventMapper extends BaseMapper<OutboxEventDO>` — L9
- `OrderRepositoryImpl implements OrderRepository`（@Repository；聚合↔DO 翻译）— L6，L7 扩历史：insert 全量写历史；update 按库中已存节数 delta 追加尾段；读出按 id 升序重组历史链。L8 扩事件：afterCommit 注册发送。**L9 改为 Outbox 写入**：构造器注入 `OutboxEventMapper` + `ObjectMapper`（不再注入 `DomainEventPublisher`）；`save()` 开头 `pullEvents()` 快照事件，持久化业务表后 `appendOutboxEvents()`——事件序列化成 payload 与业务数据写在**同一个本地事务**（要成一起成、回滚一起消失）；afterCommit/TransactionSynchronization 相关代码已移除

#### infrastructure/messaging/（RocketMQ 适配，L8；Outbox 中继 L9）
- `OrderEventPublisher implements DomainEventPublisher`（@Component）— L8：RocketMQ 适配器；topic 常量 `order-events`，destination 语法 `topic:tag`（tag=事件名），`syncSend` 发 JSON（rocketmq-spring Jackson 转换器），消息 keys=eventId；只管"怎么发"。L9 起调用方由仓储 afterCommit 变为 Outbox 中继器
- `OutboxEventRelay`（@Component）— L9：Outbox 中继器（Message Relay）。`@Scheduled(fixedDelayString="${ddd.outbox.relay-interval-ms:2000}", initialDelayString="${ddd.outbox.relay-initial-delay-ms:5000}")` 定时入口 `relayTick()`；包级公开 `relayOnce()`（测试手动驱动一轮，返回成功投递数）：`fetchPending()` 捞 status=PENDING 且退避到期的行（id 升序、LIMIT 100）→ payload 反序列化为事件 record（EVENT_TYPES 映射 5 个事件名）→ 调 `DomainEventPublisher.publishAll` 复用 L8 发送链路 → 成功 `markSent`（status=SENT + sent_at），失败 `markRetryBackoff`（retry_count+1、指数退避 5s/10s/20s/40s…封顶 5min 写 next_retry_at；重试 5 次置 FAILED）；payload 损坏/未知事件名直接 FAILED（毒消息不占轮询）。**先发后标记**：at-least-once，重复投递由消费端幂等收口（L14）
- `OrderEventLoggerConsumer`（@Component + `@RocketMQMessageListener(topic="order-events", consumerGroup="mall-order-event-logger", selectorExpression="*")`，`RocketMQListener<MessageExt>`）— L8：最小消费者，打日志并存入 sink；真实业务订阅方在 L12/L17 进场后本类退役
- `DomainEventSink`（@Component，synchronizedList 内存落点）— L8：教学/测试观察窗口，`offer` / `awaitByTag(tag, timeout)` / `all()` / `clear()`；测试断言"5 秒内到达"与载荷内容
- `ReceivedOrderEvent`（record：tag/keys/jsonBody/receivedAt:Instant）— L8
- 测试组件 `TestFaultEventPublisher`（src/test，@Component @Primary，包装 OrderEventPublisher，`failNext(n)` 前 n 次发布抛异常）— L9：故障注入；放 test 源码随组件扫描装配，保证所有 @SpringBootTest 共用一个上下文/一个 RocketMQ 消费实例

#### infrastructure/remote/（跨 BC 调用适配，L10）
- `InventoryFeignApi`（@FeignClient(name="mall-inventory", contextId="inventoryClient")）— L10：只描述线协议 `POST /api/inventories/deductions`，不做业务判断
- `InventoryDeductionRequest`（record：skuCode/quantity）— L10：Feign 请求体
- `FeignInventoryDeductionAdapter implements InventoryDeductionPort`（@Component）— L10：领域语言→HTTP 翻译；`FeignException` 翻译成 `OrderDomainException`（带 422 响应体透传），异常向上传播触发全局回滚
- `SeataFeignConfig`（@Configuration + RequestInterceptor bean）— L10：把当前线程 `RootContext.getXID()` 写进请求头 `TX_XID`；服务端由 Seata starter 的 `JakartaSeataWebMvcConfigurer` 拦截器自动读取绑定（官方"微服务框架支持"的 HTTP 传播形态）
- 测试组件 `TestRecordingInventoryPort`（src/test，@Component @Primary，记录调用+`failNext(n)` 故障注入）— L10：与 TestFaultEventPublisher 同款考虑，保单一上下文/单一消费实例

#### infrastructure/config/
- `MybatisPlusConfig`（OptimisticLockerInnerInterceptor，让 @Version 真生效）— L6

#### 启动类 — L6
- `OrderApplication` 补 `@MapperScan("com.magebyte.ddd.mall.order.infrastructure.persistence")`（第 4 讲预留的首个 Mapper 落地点）；L9 补 `@EnableScheduling`（开启 @Scheduled 检测，OutboxEventRelay 轮询生效）；L10 补 `@EnableFeignClients(basePackages = "...infrastructure.remote")`（跨 BC 调用进场）

#### application/（L10 首个应用服务进场）
- `OrderApplicationService`（@Service）— L10：`createOrder(userId, productId, skuId, productName, quantity, unitPrice, simulateRollbackFailure)` 返回订单号；`@GlobalTransactional(name="createOrder", rollbackFor=Exception.class)` + `@Transactional` 双注解（全局事务边界在应用层，与本地事务同位置）；编排顺序：`Order.create` → `addItem` → `orderRepository.save`（分支一：订单库本地事务，含 outbox 行）→ `inventoryDeduction.deduct`（分支二：Feign 跨进程）→ 返回订单号；`simulateRollbackFailure` 是教学故障开关（`ddd.seata.demo-failure-sleep-seconds` 控制停留时长，默认 20s），业务代码无此分支；跨 BC 身份翻译 `toInventorySkuCode(skuId)="SKU-"+skuId` 是临时占位，正式翻译随商品 BC/契约（防腐层讲次）落地；L11 补支付/取消用例

#### interfaces/rest/
- `GlobalExceptionHandler`（@RestControllerAdvice）— L7：`OrderDomainException` → HTTP 422（Unprocessable Entity），返回统一响应 `Result<Void>`（mall-commons）；L10 控制器进场，自动生效
- `OrderController`（@RestController `/api/orders`）— L10：`POST /api/orders` → 201 + `Result<CreateOrderResponse{orderNo}>`；最小入口只覆盖"下单+扣库存"，完整用例集与 DTO 校验规约在 L11 补齐
- `CreateOrderRequest`（record：userId/productId/skuId/productName/quantity/unitPrice/simulateRollbackFailure，JSR-380 校验）/ `CreateOrderResponse`（record：orderNo）— L10

#### interfaces/
- （见 interfaces/rest/；请求校验失败由 Spring 返回 400，领域失败 422）

### Schema (Flyway)
- `V1__init_order_schema.sql` — t_order / t_order_item / t_order_status_history / t_outbox_event / undo_log — L4
  - `t_outbox_event`（event_id 唯一键 uk_event_id、idx_status(status, next_retry_at)；列：event_id/aggregate_type/aggregate_id/event_type/payload/status(PENDING/SENT/FAILED)/retry_count/next_retry_at/created_at/sent_at）：L4 已建，L8 不读写，**L9 启用**——仓储在业务事务内写 PENDING 行，OutboxEventRelay 轮询投递成功标 SENT

### mall-inventory (支撑域) — 启动类 L4 / 四层包 L5 / 最小 AT 参与者 L10 / 预占模型待 L12

#### domain/
- `Inventory`（聚合根，最小形态）— L10：`reconstitute(id, skuCode, skuName, totalStock, availableStock, createdAt, updatedAt)`；`deduct(int quantity)` 守卫（数量必须为正、可售不得为负）；**不含**预占/释放模型与乐观锁（L12 重写，预告已写进类 Javadoc）
- `InventoryDomainException`（领域异常）— L10：接口层翻译为 HTTP 422
- `InventoryRepository`（仓储接口）— L10：`findBySkuCode(String)`；`deduct(String, int)` 返回条件 UPDATE 影响行数

#### infrastructure/persistence/
- `InventoryDO`（@TableName t_inventory；@Version/@TableLogic，乐观锁 L12 启用）/ `InventoryMapper extends BaseMapper<InventoryDO>` — L10
- `InventoryRepositoryImpl`（@Repository）— L10：`deduct` 用 `lambdaUpdate().setSql("available_stock = available_stock - n").ge(availableStock, n)` 条件更新，并发不扣负

#### application/
- `InventoryApplicationService`（@Service）— L10：`deduct(skuCode, quantity)` 标 `@Transactional`，是 AT 分支事务边界；读聚合走守卫 → 条件 UPDATE 兜底 → 影响 0 行抛库存不足（并发冲突）

#### interfaces/rest/
- `InventoryController`（@RestController `/api/inventories`）— L10：`POST /api/inventories/deductions` → 200 + `Result<Void>`；库存不足 422
- `DeductInventoryRequest`（record：skuCode/quantity，JSR-380）— L10
- `InventoryGlobalExceptionHandler`（@RestControllerAdvice）— L10：`InventoryDomainException` → 422

#### 启动类
- `InventoryApplication` — L4 `@EnableDiscoveryClient`；L10 补 `@MapperScan("...infrastructure.persistence")` 与 seata 客户端配置（`application.yml`：`seata.enabled=true`、`tx-service-group=ddd-mall-tx-group`、file 注册 + grouplist，见 ADR-02）

#### 配置/依赖
- `pom.xml` — L10：加 validation、`spring-cloud-starter-alibaba-seata`（客户端 2.2.0，父 pom 锁定）
- `src/test/resources/application-dev.yml` — L10：测试静音 `seata.enabled=false`（全局事务行为由真实环境端到端实验覆盖）
- 测试 `InventoryTest`（4 用例：扣减/不足/非法数量/重组脏数据）+ `InventoryApplicationServiceTest`（3 用例：真实 MySQL 条件扣减，@Transactional 回滚）— L10

### Schema
- `V1__init_inventory_schema.sql` — L10：t_inventory（uk_sku_code；种子 SKU-1001 示例商品 100 件，INSERT IGNORE）+ undo_log（官方 DDL，uk_undo_log(xid, branch_id)）
- Flyway 在 L10 提前启用（原计划 L12；因 AT 需要 undo_log 与最小库存表）

### mall-payment (支撑域) — 启动类 L4 / 四层包 L5 / 领域代码待 L17

(待 L17 引入 Payment 聚合根、PaymentCallbackService)

### Schema
- (空，待 L17)

### mall-product (通用域) — 启动类 L4 / 四层包 L5 / 领域代码待 L18

(待 L18 引入 Product 聚合根、ProductQueryService)

### Schema
- (空，待 L18)

## 跨 BC 调用矩阵

| 调用方 | 被调方 | 方式 | 入口 | 一致性 | 引入讲次 |
|---|---|---|---|---|---|
| mall-order | mall-inventory | 同步 HTTP（Feign，服务发现负载均衡） | `POST /api/inventories/deductions` | Seata AT 全局事务内（XID 经 `TX_XID` 头传播） | L10（防腐层加厚待 L17/L19） |

(待 L17 引入 ACL 时继续填充。形如：mall-order → mall-payment via /api/pay, schema: OpenAPI in fixtures/contracts/order-to-payment.yaml)

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
| L8 | `domain/event/` 子包：`DomainEvent` 接口、5 个 record 事件（OrderCreated/OrderPaid/OrderShipped/OrderReceived/OrderCancelled，事件名同 L1 词汇表，schemaVersion=1，消息体自包含 eventName/schemaVersion）、`DomainEventPublisher` 端口；`infrastructure/messaging/`：`OrderEventPublisher`（RocketMQ 适配器，topic `order-events`，tag=事件名，keys=eventId，syncSend JSON）、`OrderEventLoggerConsumer`（最小消费者，consumerGroup `mall-order-event-logger`）、`DomainEventSink`/`ReceivedOrderEvent`（教学内存落点）；测试 `OrderDomainEventTest`（5 用例纯 JUnit）、`OrderEventRoundTripTest`（3 用例真实 RocketMQ 往返：创建/支付 5 秒内到达实测 71ms/53ms、回滚无幽灵事件）。环境适配：父 pom 显式锁 `rocketmq-client`/`rocketmq-acl` = 5.3.1（SCA BOM 压到 5.1.4 导致 `setNamespaceV2` NoSuchMethodError，rocketmq-spring 2.3.1 需 5.3.x）；`docker-compose/rocketmq/broker.properties` `brokerIP1` 由 `rocketmq-broker` 改 `127.0.0.1`（宿主机 JVM 经发布端口连 broker，docker 网络别名宿主不可解析）；`scripts/infra.sh` up 增加 topic `order-events` 预创建（mqadmin，重试等待 broker 注册） | `Order`：新增 `pullEvents()`，`create()` 抛 OrderCreatedEvent、`recordChange()` 成功后 `raiseEventFor` 抛迁移事件，`markPaid` 校验顺序调整（先校验后落 paidAmount），类 Javadoc 补"事件可达"不变量；`OrderRepositoryImpl`：注入发布端口，save 拉事件快照 + afterCommit 注册发送 | —。`mvn clean test` 54 用例全绿（L7 为 46） |
| L9 | `infrastructure/persistence/`：`OutboxEventDO`（t_outbox_event，状态常量 PENDING/SENT/FAILED）、`OutboxEventMapper`；`infrastructure/messaging/`：`OutboxEventRelay`（@Scheduled 中继器：捞 PENDING → 复用 DomainEventPublisher 发送 → 先发后标记；失败 retry_count+1 指数退避，5 次置 FAILED）；测试：`OutboxEventRelayTest`（6 用例纯 Mockito：成功标记 SENT/失败退避重试/发送与标记间崩溃重复投递/重试耗尽 FAILED/毒消息 FAILED/退避序列）、`OutboxEventRoundTripTest`（3 用例真实 MySQL+RocketMQ：提交即 PENDING 行→中继投递→SENT 且 keys=event_id；回滚后 outbox 行与订单一起消失；故障注入首次失败 PENDING+退避、到期重试成功 SENT）、`TestFaultEventPublisher`（@Primary 故障注入）、`src/test/resources/application-dev.yml`（测试静音定时轮询）；配置 `application.yml` `ddd.outbox.relay-interval-ms=2000` / `relay-initial-delay-ms=5000`；启动类 `@EnableScheduling` | `OrderRepositoryImpl`：移除 afterCommit/TransactionSynchronization 直发逻辑与 DomainEventPublisher 注入，改为同事务写 outbox 行（注入 OutboxEventMapper + ObjectMapper，appendOutboxEvents/toJson）；`OrderApplication`：加 @EnableScheduling；`DomainEvent`/`DomainEventPublisher`/`OrderEventPublisher` Javadoc 更新为 Outbox 语义；`OrderEventRoundTripTest`：改为提交后手动驱动 relayOnce()，回滚用例增加 outbox 行消失断言，cleanup 增加 t_outbox_event 清理；`scripts/infra.sh`：第 68 行 `$t（` 改 `${t}（`（非 UTF-8 locale 下多字节字节并入变量名触发 unbound variable） | L8 的 `registerPublicationAfterCommit` 私有方法及仓储对 `DomainEventPublisher` 的依赖（afterCommit 直发路径，被 Outbox 同事务写入替代；DomainEventPublisher 端口本身保留，由 OutboxEventRelay 调用）。`mvn clean compile -q` exit 0；`mvn test -pl mall-order -am` **63 用例全绿**（L8 为 54；+6 中继单测 +3 Outbox 往返集成）；真实 jar 定时链路观测：PENDING 行提交后约 1 个轮询间隔（2s）+~20ms 完成投递并标 SENT，消费者 6ms 内收到 |
| L10 | mall-inventory 全栈最小 AT 参与者：`Inventory`/`InventoryDomainException`/`InventoryRepository`、`InventoryDO`/`InventoryMapper`/`InventoryRepositoryImpl`、`InventoryApplicationService`、`InventoryController`/`DeductInventoryRequest`/`InventoryGlobalExceptionHandler`、`V1__init_inventory_schema.sql`（t_inventory+种子+undo_log）、测试 7 个；mall-order：`InventoryDeductionPort`（domain 端口）、`infrastructure/remote/`（InventoryFeignApi/InventoryDeductionRequest/FeignInventoryDeductionAdapter/SeataFeignConfig）、`OrderApplicationService`（@GlobalTransactional 创建订单用例+教学故障开关）、`OrderController`/`CreateOrderRequest`/`CreateOrderResponse`、测试 2 个 + `TestRecordingInventoryPort`；`docs/adr/ADR-02-seata-deployment-in-teaching-environment.md`（Accepted）、`fixtures/incidents/seata-rollback-failed.md`（D2）。`mvn clean compile -q` exit 0；`mvn test` **72 用例全绿**（L9 为 63；+2 订单用例 +7 库存）。真实环境端到端三条证据：全局提交（XID 2720993956032368641，outbox 行进分支锁集、提交后两库 undo_log 异步删除）、库存不足全局回滚（订单+outbox+库存同回滚）、脏写致分支回滚失败（PhaseTwo_RollbackFailed_Unretryable，undo_log 残留待人工校准） | `OrderApplication`：@EnableFeignClients；mall-order/inventory 两份 `application.yml`（seata.enabled=true + file 注册 + grouplist，ADR-02）；两份 `application-dev.yml`（测试静音 seata）；父 pom `seata-spring-boot.version` 2.0.0→**2.2.0** 并进 dependencyManagement（apache/seata-server:2.1.0 镜像实为 2.0.0，已核验）；mall-order pom 加 openfeign/loadbalancer（SCA 不再传递）、mall-inventory pom 加 seata/validation；`docker-compose.yml` seata 镜像 seataio:2.0.0→apache:2.2.0、移除无效 SEATA_IP 与 1.x 遗留 `registry.conf` 挂载；`docker-compose/seata/application.yml` 补 `console.user.*` 与 `seata.security.secretKey` 占位符（重启循环根因修复） | — |
| L11 | 订单用例补全（支付/取消）与完整 DTO 校验规约 | `OrderApplicationService` / `OrderController` 扩展；`Order` 可能加公开方法 | — |
| ... | ... | ... | ... |

## 写入时机

每讲 post-write 末尾的"11 条门禁（8+3）"过完后，更新本文件：
- 在对应 BC 的章节下加本讲新增/修改的类与方法
- 在 `Schema` 段加本讲的 Flyway 文件
- 在 `每讲代码变更日志` 表里追加一行
- 若本讲废弃了某符号，在该符号处加 `⚠ deprecated at L-N`
