# D2：重复释放仍然守恒（L13，2026-10-08）

脱敏教学实验，真实 MySQL 独立随机 SKU，非企业生产事故。

## 输入与旧行为

初始10/10/0；L12 reserve A2、reserve B3，得到10/5/5；release A2两次，10/9/1，流水4条。A第一次释放后只应留下B3，第二次却把B余额降成1，总量依然守恒。

运行夹具 `mall-inventory/src/test/java/com/magebyte/ddd/mall/inventory/application/InventoryIdempotencyTest.java` 的 `legacy_duplicate_release_keeps_conservation_but_consumes_other_order`。

## 新行为与边界

同业务输入，带 requestKey/reservationNo 的预占各一次、A释放重放一次：10/7/3，流水3条，回执逐字段等于首次。换requestKey再次释放A被拒绝，B3保持。请求身份冲突不是可盲重试的409。

L12无归属余额不能凭数量生成历史预占单。未实现支付回调、部分释放、自动过期回收。
