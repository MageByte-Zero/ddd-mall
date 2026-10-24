# 第15讲：整单退款固定源码

基于lesson-14（908ad08）增量演进，代码快照标签为lesson-15。需求、模型、迁移、事件和测试均包含在本包中。公开仓库不包含付费文章正文。

先准备JDK21、Maven与Docker。使用第0讲已有环境与依赖；初次构建需要联网下载依赖。不要同时启动另一套使用相同消费者组的应用，也不要清空原数据卷。

```bash
export JAVA_HOME="你的 JDK 21 安装目录"
docker compose -f docker-compose/docker-compose.yml ps
mvn -q -pl mall-order -am test \
  -Dtest=FullRefundTest,FullRefundPersistenceTest,ArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

若中间件尚未运行，先按第0讲在固定工程目录启动；不要从临时副本重建共享容器。MySQL健康、Broker就绪后执行。默认数据库mall_order，连接配置在mall-order/src/main/resources/application.yml；3306、8848、9876、10911须可用。Flyway自动追加V2；不改旧V1，不清库。旧退款状态若无申请身份，须先人工对账，不猜测。

验收：FullRefundTest 16项领域行为，FullRefundPersistenceTest 6项真实MySQL/模拟结果/记录式发送检查，订单ArchitectureTest 4项依赖约束，共26项。成功可看到REFUND_SIMULATION、SHIP_REFUND_RACE、REFUND_OUTBOX等标记；竞争胜者不固定。

模拟结果只在测试中产生，生产应用没有退款HTTP入口或渠道发起逻辑。没有真实资金操作、自动库存释放/退货入库、跨服务恢复或生产验签证明。数据库测试创建独立合成订单，保留数据，将自己的待发送事实标为FAILED以隔离未来中继；此行为不是生产事件恢复流程。

规则见fixtures/requirements/full-refund.md，设计见docs/adr/ADR-06-full-refund-within-order.md。可另执行mvn test运行全量测试，包含旧库存和消息集成用例。
