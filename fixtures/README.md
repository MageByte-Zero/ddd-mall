# DDD 教学项目夹具索引

> 写每篇文章前必读；paid-column-writer 自动按 D0–D5 顺序引用本目录的脱敏输入。

## 夹具代号总表

| 代号 | 类别 | 路径 | 用途 | 首次用于 |
|---|---|---|---|---|
| **D0** | 业务需求 | `requirements/order-creation.md` | 订单创建/支付/退款的脱敏需求（状态机 + 不变量 + 用例 + 验收 + 失败样例） | 第 1 讲 |
| **D1** | 真实代码 | `../{mall-product,mall-inventory,mall-payment,mall-order}/` | 4 BC 工程代码（每讲渐进增强） | 第 4 讲起 |
| **D2** | 失败样例 | `incidents/*.md` | 库存超卖 / 重复扣减 / 消息丢失 / Seata 回滚 等脱敏失败记录 | 第 12 讲起 |
| **D3** | 跨 BC 契约 | `contracts/*.yaml` | API + 事件 schema（OpenAPI / AsyncAPI） | 第 11 讲起 |
| **D4** | 评测 | `../../evals/` | 性能压测脚本 / 契约测试 / JMeter / Gatling | 第 21 讲起 |
| **D5** | ADR | `../docs/adr/*.md` | 架构决策记录（基于 `docs/adr/template.md`） | 第 3 讲起 |

## 各讲夹具引用矩阵（spec §5.1）

| 讲 | D0 | D1 | D2 | D3 | D4 | D5 |
|---|---|---|---|---|---|---|
| 1 | ✓ | | | | | |
| 6-11 | ✓ | ✓ | | | | |
| 12 | ✓ | ✓ | ✓ | | | |
| 13-15 | | ✓ | ✓ | | | |
| 16 | | | | | | ✓ |
| 17 | | ✓ | | ✓ | | |
| 19-22 | | ✓ | | | | |
| 23-26 | | ✓ | | ✓ | | ✓ |
| 27-30 | | ✓ | | | ✓ | ✓ |

## 当前已就绪

- D0：完整（`requirements/order-creation.md`）
- D1：4 BC 父 POM + mall-order 完整骨架 + mall-product/inventory/payment 启动类（每讲按需扩展）
- D5：模板（`docs/adr/template.md`）

## 待创建（按讲次首次使用前补齐）

- D2：第 12 讲首次使用前补 4 类失败 fixture
- D3：第 11 讲首次使用前补 OpenAPI + AsyncAPI
- D4：第 21 讲首次使用前补 JMeter / Gatling 脚本

## 引用规则

每篇文章在 8 段式结构的"场景与输入"段必须显式引用本目录的具体文件路径。例如：

> 真实输入：`projects/ddd-mall/fixtures/requirements/order-creation.md`（D0）。

不要在文章里复述 D0 的全部内容，只引用 + 关键摘录。
