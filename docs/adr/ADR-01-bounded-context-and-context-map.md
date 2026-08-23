# ADR-01：4 BC 划分与上下文映射

- 状态：Accepted
- 日期：2026-08-16
- 决策人：专栏作者（业务+架构双视角自查；待真实业务/产品/研发三方代表在评审会上逐项确认）
- 关联讲次：第 3 讲
- 关联夹具：D0 `fixtures/requirements/order-creation.md`、L1 事件风暴产物、L2 子域分类决策记录

## 背景与约束

L1 事件风暴从订单创建、支付、退款链路中拆出四块候选业务：订单、库存、支付、商品；L2 给出暂定子域分类：订单=核心域，库存/支付=支撑域，商品=通用域。本讲必须回答两个问题：

1. 这四块候选业务的**限界上下文（Bounded Context，BC）边界**划在哪里？每条 BC 内维护哪一套通用语言、哪个聚合根、哪份独立 schema？
2. BC 之间如何**对话**？谁是上游、谁是下游、用同步 API 还是领域事件、哪里需要防腐层（ACL）？

约束：

- D0 状态机有 8 个状态、8 条合法迁移；金额守恒、库存守恒、状态机守恒、幂等性、事件可达 5 条不变量必须在某一条 BC 内有明确归属。
- L1 已锁定 11 个领域事件名（过去时）：OrderCreated、InventoryPreDeducted、PaymentRequested、OrderPaid、OrderCancelled、InventoryReleased、OrderShipped、OrderReceived、RefundRequested、RefundCompleted、InventoryRolledBack。BC 划分不得重命名这些事件。
- L4 将按 BC 建立 4 个 Maven 模块（mall-order/mall-inventory/mall-payment/mall-product），每个 BC 独立 MySQL schema；本讲的划分会直接决定 L4 的包结构和 Flyway 脚本位置。
- 支付渠道（支付宝/微信）未来变化频繁；商品目录由运营团队维护，与交易团队发布节奏不同。
- 本讲是战略设计，不写 Java、不起容器；但划分结论必须能被 L4/L17/L19/L23 直接引用。

## 候选方案

### 方案 A：按子域划分 4 BC（订单 / 库存 / 支付 / 商品）

- 描述：每个子域一个 BC，一个 BC = 一个独立可部署单元 = 一个 MySQL schema。订单为核心域，拥有订单状态机、金额守恒和订单聚合根；库存、支付为支撑域，各自维护预占/释放、支付回调模型；商品为通用域，提供 Product/Sku 目录。BC 间通过同步 API 或领域事件集成，跨 BC 禁止共享数据库和跨库 join。
- 优点：
  - 与 D0 子域分类一致，通用语言边界清晰：订单说"订单/订单项/支付状态"，库存说"SKU/预占/可售"，支付说"支付单/回调/退款"，商品说"SPU/SKU/类目"。
  - 变更频率不同的部分可以独立发版：支付渠道升级不迫使订单发版，商品改名不影响库存表。
  - L4 可以直接落为 4 个 Maven 模块 + 4 个 schema，测试边界和团队边界对齐。
  - 每条 BC 的不变量可独立测试：金额守恒在订单 BC 内测，库存守恒在库存 BC 内测。
- 缺点：
  - 跨 BC 流程（下单→预占库存→支付→扣减）需要处理分布式一致性，不能依赖本地事务。
  - 订单需要商品和支付的信息时，要么调用 API、要么复制快照，引入了最终一致性。
  - 团队初期需要建立契约、事件 schema 和版本治理，比单体起步成本高。

### 方案 B：订单+支付合并为"交易 BC"，库存、商品各自独立

- 描述：因为订单和支付"总是一起出现"（创建订单总要发起支付，支付回调总要改订单状态），把它们合并为一个交易 BC，共享订单+支付的表和模型。
- 优点：
  - 下单→支付流程在一个本地事务内，不需要跨 BC 一致性。
  - 初期少写一个防腐层和一套事件契约。
- 缺点：
  - **通用语言不一致**：订单说"待支付/已发货/已收货"，支付说"支付中/支付成功/退款中/已退款"；合并后同一个聚合里会出现两套状态枚举，订单状态机被支付渠道状态污染。
  - **变更频率不同**：支付渠道 SDK 升级、回调字段调整、证书轮换频率远高于订单状态机；合并后每次支付渠道变更都要回归测试订单核心流程。
  - **第三方模型侵入**：支付宝/微信的渠道字段（trade_status、transaction_id、sub_mch_id）会直接出现在订单聚合或订单表上，订单业务规则被迫感知渠道差异。
  - **扩展性差**：未来接分期、积分、优惠券等新支付方式时，交易聚合会膨胀；L17 计划的 ACL 无处安放。
  - 结论：拒绝。合并的理由是"它们总是一起出现"，但这是调用频率，不是模型一致性；D0 中订单状态机和支付单状态机是两条独立的状态机，有各自的合法迁移和不变量。

### 方案 C：按部门划 4 个服务，但不划 BC 边界（共享数据库）

- 描述：按交易部门、库存部门、支付部门、商品部门各起一个服务，但所有服务直连同一个数据库，表按前缀区分（`order_*`、`inv_*`、`pay_*`、`prod_*`）。
- 优点：
  - 团队汇报线清晰，初期可以并行开发。
  - 跨服务查询仍可写 SQL join，报表简单。
- 缺点：
  - **这不是 DDD 意义上的 BC**：Martin Fowler 指出 BC 的核心是"每个上下文内有一套统一模型"；共享库允许任一方直接读他人表，通用语言边界形同虚设。
  - 库存团队改一列定义会悄无声息地破坏订单代码，编译期不报错，运行时才炸。
  - 跨表事务让团队误以为有强一致性，一旦未来拆库（容量、合规、团队边界必然导致拆库），所有隐式依赖都要补成显式 API，成本远高于一开始就划清。
  - 结论：拒绝。部门边界是组织视角，BC 是模型视角；两者可以重合，但不能用部门表替代 BC 决策。

## 决策

采纳方案 A：按子域划分 4 BC。

| BC | 子域分类 | 职责 | 通用语言关键词（中/英） | 聚合根候选 | 独立 schema |
|---|---|---|---|---|---|
| mall-order | 核心域 | 订单生命周期、状态机、金额守恒、订单事件发布 | 订单/Order、订单项/OrderItem、金额/Money、地址/Address、订单状态/OrderStatus | Order | mall_order |
| mall-inventory | 支撑域 | SKU 库存预占/释放/回滚、库存守恒、幂等扣减 | 库存/Inventory、SKU、预占/PreDeduct、可售/Available、库存日志/InventoryLog | Inventory | mall_inventory |
| mall-payment | 支撑域 | 支付单、渠道回调、退款单、对账；对第三方渠道隔离 | 支付单/Payment、退款/Refund、回调/Callback、渠道/Channel、支付状态/PaymentStatus | Payment | mall_payment |
| mall-product | 通用域 | SPU/SKU/类目、商品上下架、商品快照数据源 | 商品/Product、SKU、类目/Category、规格/Spec | Product | mall_product |

4 BC 之间的上下文映射矩阵见本讲证据表 `research-notes/03-bounded-context-context-map-evidence.md`。关键关系：

- mall-order → mall-inventory：**Customer-Supplier**（订单是客户，库存是供应商），同步 API 预占/释放/回滚 + 库存事件异步反向通知。
- mall-order → mall-payment：**Customer-Supplier**（订单是客户），同步发起支付 + 支付事件（OrderPaid/RefundCompleted）异步驱动订单状态机；**L17 在 mall-payment 边界内对第三方渠道加 ACL**，订单不感知渠道差异。
- mall-order ← mall-product：**Conformist + Separate Ways**。订单遵从商品 BC 的 ProductId/SkuId 标识，但不共享内核；下单时把商品名、单价、规格复制为订单内的值对象快照，运行时不实时调商品。
- mall-inventory ↔ mall-product：**Separate Ways**。库存只存 SkuId 值对象，不订阅商品变更；商品改名/下架不影响已记账的库存日志。
- mall-payment → mall-order：**Open Host Service + Published Language**。支付 BC 以标准事件 schema（OrderPaid/RefundCompleted）对外发布，订单作为 consumer 订阅。
- mall-inventory → mall-order：**OHS/PL**。库存发布 InventoryPreDeducted/InventoryReleased/InventoryRolledBack。
- 其余两两关系：**Separate Ways**（无直接集成；跨 BC 协作由订单编排）。

关于商品 ID 的共享：评估过 Shared Kernel（mall-order 和 mall-inventory 共享一个 product-id JAR），结论是**不采用**。ProductId/SkuId 在订单、库存中只作为长整型标识符使用，没有跨 BC 共享的行为或不变量；复制值对象比引入共享内核更简单，也避免了 JAR 版本升级对两条 BC 的强耦合。这是本讲明确记录的"备选但放弃"。

## 后果

### 对 L4（项目骨架）

- 建立 4 个 Maven 模块：mall-order、mall-inventory、mall-payment、mall-product；每个模块独立 Spring Boot 应用、独立 application.yml、独立 Flyway 迁移目录。
- 4 个 MySQL schema 在 docker-compose 启动时自动建（L0 已实现 `init-scripts/01-create-databases.sql`）。
- mall-commons 只放无业务含义的公共工具（统一异常、BaseEntity、Money 值对象的基类），不得放任何跨 BC 共享的领域模型。

### 对 L17/L19（支付与 ACL）

- L17 在 mall-payment 内部建 Payment 聚合和 PaymentChannel 接口；支付宝/微信 SDK 字段停留在 infrastructure 层，不进入 domain 包。
- L19 把 ACL 模式从支付边界扩展到物流等外部系统；本讲已确认订单与支付之间的战略关系是 C/S + 边界预留 ACL，L17/L19 负责写代码。

### 对 L23（上下文映射代码落地）

- L23 用代码展示每种映射模式：C/S（Feign/REST 客户端 + DTO 翻译）、ACL（第三方渠道适配器）、OHS/PL（RocketMQ 事件 + schema 注册）、Conformist（订单内 ProductSnapshot 值对象）。
- 本讲的矩阵是 L23 的输入；如果 L23 实测发现某条关系需要改模式（例如库存接口不稳定，订单侧需要加 ACL），先更新本 ADR 再写代码。

### 对 L24/L25（Saga / 事件总线）

- 订单作为 Saga 编排者，监听库存/支付事件、发出补偿命令；因为本讲已把支付和库存划成独立 BC，Saga 是跨 BC 流程的必然选择，不能回退到本地事务。

### 可逆性

- BC 划分是可逆的战略假设。如果未来支付演进为核心域（自有授信/分期），可以重划支付 BC 边界或拆出风控 BC；如果商品成为差异化来源（动态定价），可以把商品从通用域升为核心域并扩展其模型。
- 回退成本：L4 之前回退仅需更新本 ADR 和证据表；L4 之后回退需要改 Maven 模块和 Flyway 脚本，但 4 个独立 schema 让合并比拆分容易。
