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
| application | 应用服务 | `OrderApplicationService`（L10 首个用例，L11 补全四个用例） | **加 ApplicationService** |
| application | 用例入参 | `CreateOrderCommand`/`PayOrderCommand`/`CancelOrderCommand`（L11，record） | **加 Command** |
| application | 用例出参 | `OrderDetail`（L11，record；含 `OrderItemView`/`StatusChangeView`） | 不加（读模型） |
| interfaces | Controller | `OrderController`（L10 创建订单入口，L11 补全用例集） | **加 Controller** |
| interfaces | 出入参 | `CreateOrderRequest`/`CreateOrderResponse`（L10，L11 补齐校验规约）、`PayOrderRequest`/`CancelOrderRequest`/`OrderDetailResponse`（L11） | 按职责加 |
| commons(公共) | 统一响应体 | `Result<T>`（L7，mall-commons） | 不加（泛型容器） |

不引入 jMolecules 等构造型注解库，也不自定义 `@AggregateRoot`/`@ValueObject` 标记注解——保持领域层零依赖（含零注解依赖），识别交给上述三个信号 + ArchUnit 分层规则。

## 4 BC 公共 API

### mall-commons (公共模块) — 统一响应 L7
- `Result<T>`（`com.magebyte.ddd.mall.commons.response.Result`）— L7：统一响应体 code/message/data，`ok()` / `ok(T)` / `error(int, String)`；成功 code=200、失败沿用 HTTP 状态码语义（如 422）。业务无关、不依赖 Spring/Jackson，供各 BC 接口层复用
- `package-info.java` — L7 固化"只放业务无关公共代码"职责

### mall-order (核心域) — 启动类 L4 / 四层包 L5 / 领域代码 L6 / 状态机 L7 / 领域事件 L8 / Outbox L9 / 全局事务用例 L10 / 用例串联 L11

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

#### domain/（跨 BC 端口，L10/L11）
- `InventoryDeductionPort`（纯 Java 端口接口）— L10：订单对"库存扣减"能力的出口抽象；`void deduct(reservationNo,skuCode,quantity)`（L13 新重载；旧二参数签名 ⚠ deprecated at L-13）；实现住基础设施层（依赖倒置），领域层不知道 Feign/HTTP。第 17/19 讲防腐层在适配器一侧加厚，本端口不动
- `InventoryReleasePort`（纯 Java 端口接口）— L11：与扣减端口**对称的逆向能力出口**；`void release(reservationNo,skuCode,quantity)`（L13 新重载；旧二参数签名 ⚠ deprecated at L-13）；同样是纯 Java 零依赖接口，实现住基础设施层。取消用例只需要"把库存还回去"这一个能力，故意不从 `InventoryDeductionPort` 借道——反向操作与正向操作是两种业务能力，混在一个端口里会让实现方被迫做 if/else

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
- `OrderNotFoundException` (领域异常) — L11：订单查不到时抛出；接口层翻译为 **404**，与 `OrderDomainException`（422）分开——"你传错订单号"和"这笔订单不能这么操作"是两回事

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

#### infrastructure/remote/（跨 BC 调用适配，L10/L11）
- `InventoryFeignApi`（@FeignClient(name="mall-inventory", contextId="inventoryClient")）— L10：L13 线协议 `POST /api/inventories/reservations`，不做业务判断；L11 加对称端点 `POST /api/inventories/releases`（`Result<Void> release(InventoryReleaseRequest)`（订单只检查成功，库存响应还含原结果快照））
- `InventoryDeductionRequest`（record：requestKey/reservationNo/skuCode/quantity）— L13 扩展 L10：Feign 请求体
- `InventoryReleaseRequest`（record：requestKey/reservationNo/skuCode/quantity）— L13 扩展 L11：归还请求体，字段形状与扣减刻意保持一致，调用方复用同一个 Feign 接口
- `FeignInventoryDeductionAdapter implements InventoryDeductionPort`（@Component）— L10：领域语言→HTTP 翻译；`FeignException` 翻译成 `OrderDomainException`（带 422 响应体透传），异常向上传播触发全局回滚
- `FeignInventoryReleaseAdapter implements InventoryReleasePort`（@Component）— L11：归还方向的同款翻译；`FeignException` → `OrderDomainException`，异常穿透到应用服务触发取消用例的全局回滚
- `SeataFeignConfig`（@Configuration + RequestInterceptor bean）— L10：把当前线程 `RootContext.getXID()` 写进请求头 `TX_XID`；服务端由 Seata starter 的 `JakartaSeataWebMvcConfigurer` 拦截器自动读取绑定（官方"微服务框架支持"的 HTTP 传播形态）
- 测试组件 `TestRecordingInventoryPort`（src/test，@Component @Primary，记录调用+`failNext(n)` 故障注入）— L10：与 TestFaultEventPublisher 同款考虑，保单一上下文/单一消费实例

#### infrastructure/config/
- `MybatisPlusConfig`（OptimisticLockerInnerInterceptor，让 @Version 真生效）— L6

#### 启动类 — L6
- `OrderApplication` 补 `@MapperScan("com.magebyte.ddd.mall.order.infrastructure.persistence")`（第 4 讲预留的首个 Mapper 落地点）；L9 补 `@EnableScheduling`（开启 @Scheduled 检测，OutboxEventRelay 轮询生效）；L10 补 `@EnableFeignClients(basePackages = "...infrastructure.remote")`（跨 BC 调用进场）

#### application/（L10 首个应用服务进场，L11 补全四用例）

- `OrderApplicationService`（@Service）— **L11 定稿形态：四个用例，四套事务语义**，差异只由一件事决定——这一步要不要同时动另一个 BC 的数据
  - `OrderDetail createOrder(CreateOrderCommand)` — `@GlobalTransactional(name="createOrder", rollbackFor=Exception.class)` + `@Transactional`：订单落库（分支一）+ Feign 扣库存（分支二）同成同败；`simulateRollbackFailure` 是教学故障开关（`ddd.seata.demo-failure-sleep-seconds`，默认 20s），业务代码无此分支
  - `OrderDetail payOrder(PayOrderCommand)` — **仅 `@Transactional`**：只动订单库，没有第二个参与者，开全局事务纯属浪费一轮 TC 通信和一张 undo_log；OrderPaid 事件随业务数据写进同一事务的 outbox 行（事务回滚则事件连出生机会都没有）
  - `OrderDetail cancelOrder(CancelOrderCommand)` — `@GlobalTransactional(name="cancelOrder", rollbackFor=Exception.class)` + `@Transactional`：与创建**对称**的全局事务；先改订单状态（分支一，含 OrderCancelled outbox 行）再逐项归还库存（分支二）；失败时订单退回 PENDING_PAY 且幽灵取消事件随之消失；`simulateReleaseFailure` 为教学故障开关
  - `OrderDetail getOrder(String orderNo)` — `@Transactional(readOnly = true)` 只读用例；查不到抛 `OrderNotFoundException`
  - 私有 `findOrder(orderNo)` 统一收口"取不到即抛"，`toInventorySkuCode(skuId)="SKU-"+skuId` 仍是跨 BC 身份翻译的临时占位（正式翻译随商品 BC/契约，防腐层讲次落地）
- `CreateOrderCommand` / `PayOrderCommand` / `CancelOrderCommand`（record，L11）— 应用层用例入参；`of(...)` 工厂把教学故障开关默认置 false，业务调用不用看见它
- `OrderDetail`（record，L11）— 应用层用例出参（读模型）：orderNo/userId/status/totalAmount/paidAmount/receiverName/items/history/version，外加 `OrderItemView`、`StatusChangeView` 两个内部 record；`static from(Order)` 做聚合→读模型投影。三个命令端点统一回它，前端一套解析逻辑吃三种用例

#### interfaces/rest/
- `GlobalExceptionHandler`（@RestControllerAdvice）— L7：`OrderDomainException` → HTTP 422（Unprocessable Entity），返回统一响应 `Result<Void>`（mall-commons）；L10 控制器进场，自动生效；**L11 加 `OrderNotFoundException` → 404**，把"资源不存在"和"业务不允许"拆成两个状态码
- `OrderController`（@RestController `/api/orders`）— L11 完整用例集：
  - `POST /api/orders` → 201 + `Result<OrderDetailResponse>`（L10 只回 orderNo，L11 改为回执行后订单状态）
  - `POST /api/orders/{orderNo}/payment` → 200 + `Result<OrderDetailResponse>`（L11）
  - `POST /api/orders/{orderNo}/cancellation` → 200 + `Result<OrderDetailResponse>`（L11）
  - `GET /api/orders/{orderNo}` → 200 + `Result<OrderDetailResponse>`（L11）
  - 控制器内不出现任何 `if (order.getStatus() == ...)`：状态判断在聚合，控制器只做协议翻译
- `CreateOrderRequest`（record，L10 → L11 补齐校验规约）/ `CreateOrderResponse`（record）— L11：补 receiverName/receiverPhone/receiverAddress 三个收货地址字段，全字段 JSR-380 校验（`@Size`/`@Pattern`，手机号 `^1[3-9]\d{9}$`、金额 `^\d{1,10}(\.\d{1,2})?$`）；"格式不对"由 Spring 返回 400，领域规则违规才 422
- `PayOrderRequest`（record，L11）— paidAmount（金额正则，最多两位小数）+ operatedBy，均 `@NotBlank`
- `CancelOrderRequest`（record，L11）— reason（`@Size(max=200)`）+ operatedBy + 可选 `simulateReleaseFailure`（`isSimulateReleaseFailure()` 做 null 安全）
- `OrderDetailResponse`（record，L11）— 接口层出参，含 `OrderItemView`/`StatusChangeView`；`static from(OrderDetail)` 做应用层读模型→接口响应投影（三层各自的视图类型互不串用）

#### interfaces/
- （见 interfaces/rest/；请求校验失败由 Spring 返回 400，领域失败 422）

### Schema (Flyway)
- `V1__init_order_schema.sql` — t_order / t_order_item / t_order_status_history / t_outbox_event / undo_log — L4
  - `t_outbox_event`（event_id 唯一键 uk_event_id、idx_status(status, next_retry_at)；列：event_id/aggregate_type/aggregate_id/event_type/payload/status(PENDING/SENT/FAILED)/retry_count/next_retry_at/created_at/sent_at）：L4 已建，L8 不读写，**L9 启用**——仓储在业务事务内写 PENDING 行，OutboxEventRelay 轮询投递成功标 SENT

### mall-inventory (支撑域) — 启动类 L4 / 四层包 L5 / 最小 AT 参与者 L10 / 归还反向能力 L11 / **预占模型 + 乐观锁 L12**

#### domain/
- `Inventory`（聚合根）— L10 最小形态 → **L12 重写为三字段守恒模型**：`totalStock == availableStock + reservedStock`（守恒，需求文档第 4 节不变量 2）。四个领域动作，每个动作末尾跑一次私有 `assertConservation(action)` 自检：
  - `reserve(int quantity)`（L12，**取代 L10 的 `deduct`**）：可售 −n、已预占 +n，总库存不动；守卫：n > 0 且 n ≤ 可售（不超卖的领域表达）
  - `confirm(int quantity)`（L12）：已预占 −n、总库存 −n，可售不动；守卫：n > 0 且 n ≤ 已预占
  - `release(int quantity)`（L11 引入守卫 → **L12 改上界**）：已预占 −n、可售 +n，总库存不动；守卫：n > 0 且 **n ≤ 已预占**（L11 的旧上界是"可售不得还到总库存之上"，拦不住并发重复释放，已替换）
  - `restock(int quantity)`（L12）：总库存 +n、可售 +n；守卫：n > 0。守恒式里唯一让等号右边变大的入口
  - `reconstitute(id, skuCode, skuName, totalStock, availableStock, reservedStock, version, createdAt, updatedAt)`（L12 签名加 `reservedStock` 与 `version`）：**重组即校验守恒**，违反当场抛 `InventoryDomainException`
  - `snapshot()`（L12）→ `StockSnapshot`
  - `version()`（L12）：乐观并发版本号，领域层只读，由持久化层维护自增
- `StockSnapshot`（值对象，record：`totalStock/availableStock/reservedStock`）— L12：不可变三数快照，供流水记 before/after；`conservative()` 自检守恒
- `InventoryConcurrencyException`（并发异常）— L12：独立成类，与"业务不允许"分开；接口层翻译 **409 Conflict**
- `InventoryDomainException`（领域异常）— L10：接口层翻译为 HTTP 422
- `InventoryRepository`（仓储接口）— L10 → **L12 改为双写路径**：
  - `findBySkuCode(String)`
  - `int save(Inventory)`— 聚合整体保存 + 乐观锁（`WHERE version = ?`），0 行 = 版本冲突
  - `int reserveAtomically(String skuCode, int quantity)`— 原子条件更新（`WHERE available_stock >= n`），热点预占路径
  - `void appendLog(skuCode, changeType, quantity, before, after)`— 库存流水
  - ⚠ deprecated at L-12：`deduct(String, int)`、`release(String, int)`（条件 UPDATE 仓储方法，被 `save` / `reserveAtomically` 取代）

#### infrastructure/persistence/
- `InventoryDO`（@TableName t_inventory；@Version/@TableLogic；**L12 加 `reservedStock`**）/ `InventoryMapper extends BaseMapper<InventoryDO>` — L10
- `InventoryLogDO` / `InventoryLogMapper extends BaseMapper<InventoryLogDO>`（@TableName t_inventory_log）— L12：库存流水，**不做聚合的一部分**（审计需求，塞进聚合会随订单量无限膨胀），由应用服务在用例成功后经 `appendLog` 写入同一本地事务
- `InventoryRepositoryImpl`（@Repository）— L10 → **L12 重写**：`save` 走 `updateById` + `@Version`（由乐观锁拦截器补 `WHERE version = ?`）；`reserveAtomically` 走 `lambdaUpdate().setSql("available_stock = available_stock - n").setSql("reserved_stock = reserved_stock + n").ge(availableStock, n)` —— 相对扣减 + 行锁原子，防超卖最后一道闸门

#### infrastructure/config/
- `MybatisPlusConfig`（@Configuration，`OptimisticLockerInnerInterceptor`）— L12：让 `@Version` 真正生效。不注册该拦截器，`version` 只是普通整数列

#### application/
- `InventoryApplicationService`（@Service）— L10 → **L12 五个用例；L13 匿名 reserve/release/confirm 仅作 deprecated 教学回归，新入口使用 IdempotentInventoryService**：
  - `reserve(skuCode, quantity)`— `@Transactional`，AT 分支；领域守卫 + `reserveAtomically` 原子预占（**热点路径，不走乐观锁**）
  - `release(skuCode, quantity)`— `@Transactional`；领域守卫 + `save` 乐观锁，冲突抛 `InventoryConcurrencyException`
  - `confirm(skuCode, quantity)`— `@Transactional`；同上走乐观锁（匿名教学旧入口；L14 的 OrderShipped v2 使用 IdempotentInventoryService.confirm，L25 扩展多订阅）
  - `restock(skuCode, quantity)`— `@Transactional`；同上走乐观锁
  - `get(skuCode)`— `@Transactional(readOnly = true)`，返回 `InventoryView`
  - 私有 `saveOrThrowConcurrent(...)`：版本冲突**不重试**（可重复读下同一事务重读是同一快照；重试需新事务，会把操作从 AT 分支摘出去）
- `InventoryView`（record 读模型：skuCode/skuName/totalStock/availableStock/reservedStock）— L12：三个数一起给，只给可售无法区分"在库 100 被占 5"和"在库 95 没人占"

#### interfaces/rest/
- `InventoryController`（@RestController `/api/inventories`）— L10 → **L12 五个端点**：
  - `POST /api/inventories/deductions` → 422；⚠ deprecated at L-13，保留路径但拒绝匿名写入。新 `/api/inventories/reservations` 使用身份请求 → 200 + 原结果快照
  - `POST /api/inventories/releases` → 200 + 原结果快照；L13 必须有 requestKey/reservationNo，缺字段400，生命周期不允许422，身份冲突/锁等待409
  - `POST /api/inventories/confirmations` → 200 + 原结果快照；L13 必须有身份，与释放终态互斥
  - `POST /api/inventories/restocks` → 200（L12）
  - `GET /api/inventories/{skuCode}` → 200 + `Result<InventoryResponse>`（L12）
- `DeductInventoryRequest`（record：skuCode/quantity，JSR-380）— L10；L12 语义改预占
- `ReleaseInventoryRequest`（record：skuCode/quantity，JSR-380；quantity `@Min(1)`）— L11；L12 上界语义改"已预占"
- `ConfirmInventoryRequest` / `RestockInventoryRequest`（record，JSR-380）— L12
- `InventoryResponse`（record：五字段）— L12
- `InventoryGlobalExceptionHandler`（@RestControllerAdvice）— L10：`InventoryDomainException` → 422；**L12 加 `InventoryConcurrencyException` → 409**

#### 启动类
- `InventoryApplication` — L4 `@EnableDiscoveryClient`；L10 补 `@MapperScan("...infrastructure.persistence")` 与 seata 客户端配置（`application.yml`：`seata.enabled=true`、`tx-service-group=ddd-mall-tx-group`、file 注册 + grouplist，见 ADR-02）

#### 配置/依赖
- `pom.xml` — L10：加 validation、`spring-cloud-starter-alibaba-seata`（客户端 2.2.0，父 pom 锁定）
- `src/test/resources/application-dev.yml` — L10：测试静音 `seata.enabled=false`；**L12 加 `spring.datasource.hikari.maximum-pool-size: 64`**（默认 10 条连接会把并发实验跑成串行）
- 测试 — L10 起 7 个；L11 +7；**L12 重写并扩到 26 个**：
  - `InventoryTest`（14，纯单测）：预占挪数/不足/非法数量/确认出库/出库越界/释放归还/**释放越界（总库存允许但已预占不允许）**/释放非法数量/补货/预占+释放回原值/预占+确认永久消耗/**重组拒绝违反守恒的脏数据**/重组拒绝负数/快照不可变
  - `InventoryApplicationServiceTest`（9，真实 MySQL）：预占/不足/未知 SKU/释放/释放越界/确认出库/出库越界/补货/查询三件套
  - `InventoryConcurrencyTest`（3，并发实验，并发度 64）：防超卖 / 写策略对比 / 并发释放撞乐观锁

### Schema
- `V1__init_inventory_schema.sql` — L10：t_inventory（uk_sku_code；种子 SKU-1001 示例商品 100 件，INSERT IGNORE）+ undo_log（官方 DDL，uk_undo_log(xid, branch_id)）
- `V2__inventory_reservation_model.sql` — **L12**：加 `reserved_stock INT NOT NULL DEFAULT 0`；**历史数据校准** `UPDATE t_inventory SET reserved_stock = total_stock - available_stock WHERE total_stock > available_stock`（把第 10/11 讲"被扣掉却无记录的件数"解释为仍被预占，是唯一不丢信息的解释；真实项目须业务方核对预占单据）；建 `t_inventory_log`
- `V3__inventory_idempotency.sql` — L13：预占单、动作回执两表及日志身份；唯一约束与旧余额边界见下方 L13 增量。
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
| mall-order | mall-inventory | 同步 HTTP（Feign，服务发现负载均衡） | `POST /api/inventories/reservations` | Seata AT 全局事务内（XID 经 `TX_XID` 头传播） | L10；**L12 语义由"扣减"改为"预占"**；L13 新接口带预占单和请求键，旧匿名扣减拒绝写入 |
| mall-order | mall-inventory | 同步 HTTP（Feign，同一 Feign 接口的反向端点） | `POST /api/inventories/releases` | Seata AT 全局事务内（取消用例，与下单对称） | L11；**L12 上界语义由"总库存"改为"已预占"**，冲突时 409 |

跨 BC 契约的单一来源是 `fixtures/contracts/`（L11 首次启用）：`order-to-inventory.yaml`（OpenAPI 3.0.3，订单→库存的扣减 + 归还两个端点）、`order-events.yaml`（AsyncAPI 2.6.0，订单对外发布的 5 个领域事件）。两边代码改动前先对齐契约；第 17、23、25、26 讲的集成与契约测试以它们为唯一参照。

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
| L11 | mall-order：应用层 `CreateOrderCommand`/`PayOrderCommand`/`CancelOrderCommand`（record + `of()` 工厂）、`OrderDetail`（record 读模型，含 `OrderItemView`/`StatusChangeView`）；领域层 `InventoryReleasePort`（与 `InventoryDeductionPort` 对称的逆向端口）、`OrderNotFoundException`；`infrastructure/remote/` 加 `FeignInventoryReleaseAdapter` + `InventoryReleaseRequest`，`InventoryFeignApi` 加 `release`；接口层加 `PayOrderRequest`/`CancelOrderRequest`/`OrderDetailResponse`，`OrderController` 补 `POST /{orderNo}/payment`、`POST /{orderNo}/cancellation`、`GET /{orderNo}`，`CreateOrderRequest` 补收货地址三字段 + 全字段 JSR-380 校验，`GlobalExceptionHandler` 加 `OrderNotFoundException` → 404；测试 `OrderApplicationServiceTest` +8（支付/重复支付/金额不符/取消/取消已支付/归还失败/订单不存在/详情）、`TestRecordingReleasePort`。mall-inventory：`Inventory.release(int)`（上界守卫：可售不得还到总库存之上）、`InventoryRepository.release` + `InventoryRepositoryImpl.release`（`available_stock + n <= total_stock` 条件更新）、`InventoryApplicationService.release`（AT 分支）、`InventoryController` `POST /api/inventories/releases` + `ReleaseInventoryRequest`，测试 +7。**D3 契约首次启用**：`fixtures/contracts/order-to-inventory.yaml`（OpenAPI 3.0.3，扣减 + 归还）、`fixtures/contracts/order-events.yaml`（AsyncAPI 2.6.0，5 事件）。`mvn clean compile -q` exit 0；`mvn test` **87 用例全绿**（L10 为 72；+15） | `OrderApplicationService`：从"单用例"扩成**四用例四套事务语义**（create/cancel = `@GlobalTransactional`，pay = 仅 `@Transactional`，get = `readOnly`），`createOrder` 入参由 7 个散参收敛为 `CreateOrderCommand`、返回由订单号升级为 `OrderDetail`；`OrderController.create` 返回类型 `CreateOrderResponse` → `OrderDetailResponse`；`CreateOrderRequest` 补 receiverName/receiverPhone/receiverAddress；`Inventory` 类 Javadoc 从"L10 最小形态"改写为扣减 + 归还双向守卫 | `CreateOrderResponse` 退居次要（控制器不再回它，保留给"只关心订单号"的调用方）。真实环境端到端证据：创建（201/PENDING_PAY/库存 100→98）、支付（200/PAID/outbox OrderPaid SENT）、重复支付 422 `非法状态迁移: PAID -> PAID`、取消已支付 422 `PAID -> CANCELLED`、取消（200/CANCELLED/库存 95→98）、取消用例故障注入（422/20.14s/订单侧 Rollbacked + 库存侧 PhaseTwo_Rollbacked，outbox 无 OrderCancelled 幽灵行） |
| L12 | mall-inventory 全栈重写为**预占模型**：`Inventory` 三字段守恒（`total = available + reserved`）+ 四个动作 `reserve`/`confirm`/`release`/`restock` + 每动作守恒自检 + 重组校验 + `snapshot()`；`StockSnapshot`（record 值对象）；`InventoryConcurrencyException`（409）；`InventoryRepository` 双写路径 `save`（聚合整体保存 + `@Version` 乐观锁）/ `reserveAtomically`（原子条件更新）/ `appendLog`；`InventoryLogDO`/`InventoryLogMapper`（t_inventory_log）；`infrastructure/config/MybatisPlusConfig`（OptimisticLockerInnerInterceptor，让 `@Version` 真生效）；应用层 `InventoryApplicationService` 五用例（reserve/release/confirm/restock/get）+ 读模型 `InventoryView`；接口层 `InventoryController` 五端点（新增 `/confirmations`、`/restocks`、`GET /{skuCode}`）+ `ConfirmInventoryRequest`/`RestockInventoryRequest`/`InventoryResponse`，`InventoryGlobalExceptionHandler` 加 409；Flyway `V2__inventory_reservation_model.sql`（加 `reserved_stock` + 历史数据守恒校准 + 建 `t_inventory_log`）；`docs/adr/ADR-03-inventory-reservation-model-and-write-strategy.md`（Accepted）；`fixtures/incidents/inventory-oversold.md`（D2，首次启用）。测试 26 个（`InventoryTest` 14 / `InventoryApplicationServiceTest` 9 / `InventoryConcurrencyTest` 3）。`mvn clean compile -q` exit 0；`mvn test` **99 用例全绿**（L11 为 87；mall-inventory 14→26，mall-order 73 不变） | `Inventory`：删除 `deduct`，`release` 上界由"总库存"改为"已预占"，`reconstitute` 签名加 `reservedStock`/`version`；`InventoryDO` 加 `reservedStock`；`InventoryRepository`：`deduct(String,int)`/`release(String,int)` 被 `save`/`reserveAtomically` 取代（标 `⚠ deprecated at L-12`，L13 真删）；`DeductInventoryRequest`/`ReleaseInventoryRequest` 语义改写；`src/test/resources/application-dev.yml` 加 Hikari `maximum-pool-size: 64`；需求文档失败样例段标注 `inventory-oversold.md` 已补 | `Inventory#deduct`（被 `reserve` 取代）；`InventoryRepository#deduct` 与 `#release`（条件 UPDATE 仓储方法，⚠ deprecated at L-12，L13 真删）。真实环境端到端证据：创建（200/库存 100/98/**2** 守恒成立）、支付（200/库存不变——已支付订单继续占着库存是正确的）、取消（200/库存 100/98/2 释放生效）、释放越界 422「释放数量超过已预占」、库存不足 422 全局回滚（订单 0 行）、故障注入取消回滚（422/20.246s/库存回到 100/96/4/订单退回 PENDING_PAY）。并发实测（并发度 64）：防超卖 50/1000 成功 50 被拒 950；写策略对比 乐观锁 成功 17 冲突 983（73.8 ms/件）vs 原子条件更新 成功 1000（11.0 ms/件）；并发释放 成功 2 冲突 98 |
| ... | ... | ... | ... |

## 写入时机

每讲 post-write 末尾的"11 条门禁（8+3）"过完后，更新本文件：
- 在对应 BC 的章节下加本讲新增/修改的类与方法
- 在 `Schema` 段加本讲的 Flyway 文件
- 在 `每讲代码变更日志` 表里追加一行
- 若本讲废弃了某符号，在该符号处加 `⚠ deprecated at L-N`

## L13 增量（lesson-13 快照，2026-10-08）

- **domain 新增**：不可变实体 `Reservation`（class，访问器 reservationNo/skuCode/quantity/state）与 `State.RESERVED/RELEASED/CONFIRMED`；`requireSame` 保护内容不可变，`finish` 保护整笔生命周期；`InventoryOperation`、`InventoryOperationResult`、`InventoryOperationStore`（跨层持久化端口）；`InventoryIdempotencyException`。
- **application 新增**：`IdempotentInventoryService.reserve/release/confirm(InventoryOperation)` → `InventoryOperationResult`；各有 `@GlobalLock @Transactional`。先 SKU `FOR UPDATE`，再请求当前锁定读；重放回第一次快照；同键异参拒绝，换 key 的同业务动作拒绝。新路径在 SKU 锁内 `InventoryRepository.save`，不宣称沿用 L12 热点原子写吞吐。
- **infrastructure 新增**：`InventoryOperationMapper` 与 `InventoryOperationStoreImpl`，只依赖 domain；SKU/回执/预占单使用当前锁定读；流水含业务单号与请求键。
- **Schema V3**：新表 `t_inventory_reservation`（uk_reservation_no）、`t_inventory_operation`（uk_request_key、uk_reservation_action）；`t_inventory_log` 增加可空 reservation_no/request_key；旧库存余额不推断归属。身份 VARCHAR 使用 utf8mb4_bin。
- **HTTP 当前协议**：`POST /reservations`、`POST /releases`、`POST /confirmations` 均收 `InventoryOperationRequest(requestKey,reservationNo,skuCode,quantity)`，返回 `Result<InventoryOperationResult>`。旧 `/deductions` 保留路径但 422 拒绝；旧二字段 `/releases`、`/confirmations` 输入400。
- **⚠ deprecated at L-13**：`InventoryApplicationService.reserve/release/confirm(String,int)` 与 `InventoryController.reserve(DeductInventoryRequest)`；前者只供 L12 教学回归，后者 fail closed。`ReleaseInventoryRequest`、`ConfirmInventoryRequest` 类保留一讲但已无 HTTP 绑定。不能混用旧 release 消耗有归属的新预占。
- **订单同步**：端口 `InventoryDeductionPort.deduct(reservationNo,skuCode,quantity)` / `InventoryReleasePort.release(reservationNo,skuCode,quantity)` 新重载，生产 Feign 适配器覆盖；旧二参数方法 deprecated 且生产适配器拒绝。默认新重载 fail closed 防未迁移适配器漏掉身份；测试 recording ports 明确覆盖。Feign `/reservations`，请求 key 为 `RESERVE:`/`RELEASE:` + reservationNo。订单用例传 `saved.orderNo()+":"+skuCode`。一次 POST /orders 每次生成新单号，未实现其入口重放去重。
- **测试**：`InventoryIdempotencyTest`（真实 MySQL，无清库，独立 L13 SKU），`ArchitectureTest`（inventory 领域无外层依赖、基础设施不依赖应用/接口、应用不依赖基础设施/接口）。
- 业务依据 D0 L13 契约；跨 BC 依据 `fixtures/contracts/order-to-inventory.yaml` 1.2.0；失败依据 `fixtures/incidents/duplicate-deduct.md`；决策依据 ADR-04。本讲不可变代码快照标签为 `lesson-13`，读者可从公开仓库取得；文章源码资料包提供相同实现。

## L14 增量（本地快照，2026-10-08）

- 订单：OrderShippedEvent schemaVersion 2 + ShipmentLine事实快照；Order.markShipped拒绝重复SKU身份；ShipOrderCommand/ShipOrderRequest与POST /api/orders/{orderNo}/shipment调用本地应用事务。旧raise签名deprecated保留v1回归，库存v2订阅拒绝猜缺失明细。
- 投递：OrderEventPublisher验证SendResult==SEND_OK；可配置ddd.order.events-topic便于独立夹具。OutboxEventRelay只抓候选；独立OutboxEventDelivery.deliver(id)在@GlobalLock/@Transactional当前锁定读、重查状态后发送+标SENT；被阻塞行保留待重试，不算发送失败。
- 库存：ShipmentFact/ConsumedEventStore领域端口；ShipmentApplicationService本地事务编排持久化事件去重+所有库存出库，稳定CONFIRM:reservationNo、排序SKU锁；ConsumedEventStoreImpl唯一插入冲突后当前共享读指纹；V4建t_consumed_event。
- 接口：InventoryShipmentConsumer处于interfaces.messaging；严格v2字段、数量整数、已知字段标准化SHA-256指纹；异常传播；默认topic order-events/group inventory-shipment-v2，只有OrderShipped tag。库存新增rocketmq-spring 2.3.1依赖与nameserver配置，业务生产重试预算16，Native故障夹具专用组预算2。
- 验证：SendStatus单测、ShipmentReliabilityTest并发/冲突/合法B/多SKU原子回滚/严格协议、ShipmentOutboxTest真实发货用例v2事实；ReliabilityProcess/OutboxProcess独立进程故障。实际结果以当前第14讲evidence为准，不把测试源存在当通过。
- 调整：一个最小库存业务订阅从L25前移L14（用户批准）；L25保留多订阅、路由和Outbox演进。未实现跨事件有序、自动DLQ治理、去重TTL或远程副作用原子化。
