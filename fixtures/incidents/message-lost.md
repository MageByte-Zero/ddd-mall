# 消息失败恢复与重复出库 fixture

- 日期：2026-10-08；脱敏合成订单 A/B，真实 MySQL、RocketMQ、Java 子进程。
- 基线：同 SKU 总库存10，A预占2、B预占3；10/5/5。
- 错误窗口：业务出库已提交但回调未成功；先独立写消费完成会漏业务，先独立出库再记消费会重复副作用。
- 输入入口：mall-inventory/src/test/java/com/magebyte/ddd/mall/inventory/fixture/ReliabilityProcess.java；mall-order/.../fixture/OutboxProcess.java。文章读者包包含编排 run-reliability.py。
- 观察：同eventId到达计数、t_consumed_event、t_inventory_operation、t_inventory_reservation、t_inventory及CONFIRM流水分别核对。
- 保持身份重试，A仅一次；B合法独立出库；同id异内容失败且不改库存。原始失败/成功日志留在专栏 evidence 所指目录。
- 结果边界：原生Push回调用于可控故障，实际starter另验；端口故障仅子进程客户端路由，不停止Broker；AT使用真实TC。非SEND_OK仅单测状态替身。不伪装掉电、毁盘、任意网络ACK包损失或生产事故。
