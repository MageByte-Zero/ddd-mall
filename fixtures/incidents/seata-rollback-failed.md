# 失败样例：Seata AT 分支回滚失败（脏写致 after-image 校验不通过）

> 脱敏教学夹具；2026-09-05 在 ddd-mall 本地环境真实复现（Seata 2.2.0、MySQL 8.0.39）。
> 首次使用：第 10 讲《Seata AT 模式：跨 BC 事务怎么保一致？》。

## 背景

Seata AT 模式的二阶段回滚不是"无条件恢复"：RM 用 undo_log 里的
**后镜像（after-image）**与当前行数据比对，一致才执行前镜像反向补偿。
如果分支一阶段提交之后、二阶段回滚之前，有全局事务之外的写入改了这行
（脏写），后镜像校验失败，回滚中止——这是 AT 模式最重要的保护，也是
运维上最危险的边界。

## 复现步骤（真实执行记录）

1. 启动 TC（apache/seata-server:2.2.0）与 mall-order、mall-inventory 真实 jar。
2. 发起带教学故障开关的创建订单请求（quantity=3）：

   ```bash
   curl -X POST http://localhost:8084/api/orders -H "Content-Type: application/json" \
     -d '{"userId":9529,"productId":1001,"skuId":1001,"productName":"示例商品",
          "quantity":3,"unitPrice":"99.00","simulateRollbackFailure":true}'
   ```

   用例在一阶段（订单本地写入 + 库存扣减）全部成功后停留 20 秒再抛异常，
   模拟"业务阶段全部成功、全局决策阶段失败"。
3. **停留窗口内（第 6 秒）手工制造脏写**——全局事务之外的直接 UPDATE：

   ```sql
   UPDATE mall_inventory.t_inventory SET available_stock = 5
   WHERE sku_code = 'SKU-1001';   -- 一阶段提交后的值是 95
   ```

4. 停留结束，用例抛异常 → `@GlobalTransactional` 触发全局回滚。

## 真实结果（2026-09-05 日志原样摘录）

**库存侧 RM（`logs/mall-inventory.out`）**：

```
11:01:20.798  INFO  RmBranchRollbackProcessor : rm handle branch rollback process:
    BranchRollbackRequest{xid='172.18.0.4:8091:2720993956032368645',
    branchId=2720993956032368646, branchType=AT,
    resourceId='jdbc:mysql://localhost:3306/mall_inventory'}
11:01:20.873 ERROR  DataSourceManager : branchRollback failed. branchType:[AT],
    xid:[172.18.0.4:8091:2720993956032368645], branchId:[2720993956032368646],
    reason:[Branch session rollback failed because of dirty undo log, please
    delete the relevant undolog after manually calibrating the data.]
11:01:20.873  INFO  AbstractRMHandler : Branch Rollbacked result:
    PhaseTwo_RollbackFailed_Unretryable
```

**TC（seata-server 容器日志）**：

```
11:01:20.943 BranchRollbackResponse{... branchStatus=PhaseTwo_RollbackFailed_Unretryable}
11:01:20.944 ERROR SessionHelper : The Global session ...645 has changed the
    status to RollbackFailed, need to be handled it manually.
11:01:20.947 ERROR DefaultCore : Rollback branch transaction fail and stop retry,
    xid = ...645 branchId = ...646
```

**数据库终态**：

| 位置 | 状态 |
|---|---|
| `mall_inventory.t_inventory` SKU-1001 | available_stock=5（脏数据被保留——AT 拒绝盲目恢复，这是保护不是缺陷） |
| `mall_inventory.undo_log` | 残留 1 行（xid=...645），等待人工校准后删除 |
| `mall_order.t_order` user_id=9529 | 0 行（订单分支未达一阶段提交，本地已回滚，无需注册） |

## 处理手册（本夹具的实际操作）

1. 人工核对业务语义，把数据校准到正确终态（全局事务应整体回滚 → 恢复扣减前数值）：

   ```sql
   UPDATE mall_inventory.t_inventory SET available_stock = 98
   WHERE sku_code = 'SKU-1001';
   ```

2. 删除残留 undo_log（RM 的错误信息明确要求这一步）：

   ```sql
   DELETE FROM mall_inventory.undo_log
   WHERE xid = '172.18.0.4:8091:2720993956032368645';
   ```

3. TC 侧 RollbackFailed 会话为终态（Unretryable），重启或等待超时后不再重试；
   生产环境此状态必须接入告警（控制台全局事务列表可见）。

## 教训（写进第 10 讲正文）

- AT 的回滚能力依赖"分支数据只被全局事务内的写入修改"这一前提；
  全局锁只保护全局事务之间，挡不住绕过 Seata 数据源代理的直连写入。
- 回滚失败不是数据丢了，而是 Seata 拒绝用错误的前镜像覆盖被改过的数据——
  宁可停下等人，不可盲目补偿。
- 监控要求：undo_log 残留行数、RollbackFailed 会话数必须有告警。
