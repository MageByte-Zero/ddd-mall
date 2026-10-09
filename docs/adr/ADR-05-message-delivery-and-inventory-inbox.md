# ADR-05：发货事实采用 Outbox、库存 Inbox 与稳定出库身份

- 状态：本地实现待正文审阅；2026-10-08。
- 约束：已有 AT 创建/取消、支付后仍预占、整笔出库；消息可能重发与重投；订单与库存库独立。

## 决策

发货只在订单本地事务写 SHIPPED 与 OrderShipped v2 的稳定预占快照。库存接口层翻译公开事件为本地 ShipmentFact，不依赖订单 Java 类；应用层编排所有 SKU 的原子出库，按 SKU 排序锁定。持久化 Inbox subscription+eventId 唯一，核验已知业务字段标准化指纹；与库存副作用处于共同事务。CONFIRM:reservationNo 保护业务动作，独立 eventId 不绕过它。

Outbox 候选只定位 ID，独立 Spring bean OutboxEventDelivery 通过 @GlobalLock+@Transactional+FOR UPDATE 验证全局提交可见性再发送，SEND_OK 才更新 SENT。保持先发后标记，接受传输重复。消息回调只在本地提交后成功返回，异常保留重试；FAILED 与 DLQ 分别有人工修复/重放责任。

## 备选

只按 Broker msgId：生产者重发可获得新物理消息身份。只按订单号：其他合法生命周期事件会被误伤。独立 Redis 去重标记：与库存 MySQL 不具同一本地事务。回调先确认后执行业务：失败会丢恢复机会。同步调用库存出库：可以选，但发货依赖库存即时可用，会改变已批准异步边界。事务消息：可替代投递协调，但不能自动覆盖库存本地幂等/跨库副作用。

## 代码证据

ShipmentApplicationService、ConsumedEventStoreImpl、V4、InventoryShipmentConsumer、OrderShippedEvent、OrderApplicationService.shipOrder、OutboxEventDelivery，以及 ShipmentReliabilityTest/ShipmentOutboxTest 和两个子进程夹具。第一版重复 INSERT 后 FOR UPDATE 升级产生死锁；不可变指纹改为当前共享读核验，完整事务死锁仍向 MQ 传播并重试。

## 可逆性

可替换 MQ 适配与消费记录存储，但保持事实身份、业务身份与共同提交边界。若改远程副作用，须重新设计接收与补偿，不能延用“同一本地事务”结论。改同步出库先设计新边界与迁移，旧 Inbox/动作回执不得直接删掉。普通并发消费顺序、多中继租约、去重保留期限、生产集群容灾留待后续设计。
