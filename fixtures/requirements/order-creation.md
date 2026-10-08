# D0：订单创建需求（脱敏）

> 用途：第 1/6/7/8/9/10/11/15/24/27/29 讲的统一脱敏需求夹具。
> 来源：虚构；不引用真实公司名、真实订单数据。
> 最后更新：2026-08-08

## 1. 业务目标

电商平台用户从浏览商品到完成支付，订单作为核心域，承担以下目标：

- 用户下单后，订单状态可追溯；
- 库存与订单保持一致，不超卖；
- 支付回调与订单状态机联动；
- 退款按状态机回滚库存。

## 2. 范围

**核心域**：`订单`。
**支撑域**：`库存`、`支付`。
**通用域**：`商品`。

## 3. 状态机

```
PENDING_PAY → PAID → SHIPPED → RECEIVED
     ↓
CANCELLED
     ↓
REFUND_REQUESTED → REFUNDED
```

合法迁移：

- `PENDING_PAY → PAID`（支付回调成功）
- `PENDING_PAY → CANCELLED`（用户主动取消 / 超时取消）
- `PAID → SHIPPED`（商家发货）
- `PAID → REFUND_REQUESTED`（用户申请退款）
- `SHIPPED → RECEIVED`（用户确认收货）
- `SHIPPED → REFUND_REQUESTED`（仅整单退款）
- `REFUND_REQUESTED → REFUNDED`（支付 BC 退款完成）
- `REFUNDED` 是终态

非法迁移示例：

- `RECEIVED → CANCELLED`（已确认收货不能取消）
- `CANCELLED → PAID`（已取消不能变已支付）
- 任意状态跳过中间步骤直接到 RECEIVED

## 4. 不变量

1. **金额守恒**：`订单总金额 == ∑ 订单项小计`
2. **库存守恒**：`已预占库存 + 可售库存 == 总库存`
3. **状态机守恒**：状态变更必须经过合法迁移
4. **幂等性**：同一 `requestKey` 的库存动作只生效一次，异参拒绝；同一 `reservationNo + action` 不能换 key 再执行（L13 已实现）。订单创建请求去重与支付回调去重不因此自动成立。
5. **事件可达**：订单状态变更后，对应的领域事件必须发布（Outbox 兜底）

## 5. 用例

| 用例 | 触发 | 关键结果 |
|---|---|---|
| 创建订单 | POST /api/orders | 订单进入 PENDING_PAY，库存预占 |
| 支付订单 | 第三方支付回调 | PENDING_PAY → PAID，事件 OrderPaid |
| 取消订单 | 用户 / 超时 | PENDING_PAY → CANCELLED，库存释放 |
| 申请退款 | 用户 | PAID → REFUND_REQUESTED |
| 退款完成 | 支付 BC | REFUND_REQUESTED → REFUNDED，库存回滚 |

## 6. 验收条件

- 状态机非法迁移被拦截，返回 422；
- 1000 并发下单同一 SKU，预占库存数不超总库存；
- 支付回调重复 100 次，订单只被支付一次；
- 订单状态变更后 5 秒内对应事件到达 RocketMQ 消费者；
- Seata AT 模式下订单+库存同时成功或同时回滚。

## 7. 非功能要求

- 订单创建 P99 < 300ms；
- 状态机查询 P99 < 50ms；
- 4 BC 在 Nacos 独立注册，互不感知对方实现。

## 8. 失败样例（写作时必须触发至少 1 类）

- 库存超卖（D2 fixture：`fixtures/incidents/inventory-oversold.md`，第 12 讲补；含守恒被破坏的存量数据形态与并发超卖窗口的实测数据）
- 重复扣减（D2 fixture：`fixtures/incidents/duplicate-deduct.md`，第 13 讲首次使用前补）
- 消息丢失（D2 fixture：`fixtures/incidents/message-lost.md`，第 14 讲首次使用前补）
- Seata 回滚失败（D2 fixture：`fixtures/incidents/seata-rollback-failed.md`，第 10 讲首次使用前补）

## L13 库存动作契约（2026-10-08）

- 预占单 `reservationNo` 在库存 BC 内唯一；本教学单SKU订单使用 `orderNo:skuCode`，正式多行同SKU订单须稳定订单行身份，不能复用本讲拼接方式掩盖歧义。
- `requestKey` 在库存 BC 内全局唯一，跨动作也不能复用；RESERVE 与 RELEASE 使用不同键。相同键且业务身份、动作、SKU、数量完全相同，返回第一次结果快照。
- 预占状态只允许 RESERVED→RELEASED 或 RESERVED→CONFIRMED；只支持整笔释放/出库，终态不能重复新动作。换请求键不绕过业务唯一性。
- 幂等结果、业务预占、库存、流水处于同一本地事务；AT 场景结果是分支结果，必须经全局锁检查后才能向另一事务重放，不能将一阶段结果当全局成功。
- L12 已预占余额不含订单归属，V3 不自动回填、不猜历史预占单；历史订单须先对账迁移或用 L12 完成教学回收再切换。新协议不能释放缺失预占单的余额。
- 旧 `/deductions` 路径保留但拒绝执行；`/releases`、`/confirmations` 必须携带新字段。旧 Java 方法只保留教学回归，不作为新业务或生产回退。
- 补货请求幂等、部分释放、自动过期回收、完整支付/MQ业务和订单创建入口去重不在 L13 实现范围。
