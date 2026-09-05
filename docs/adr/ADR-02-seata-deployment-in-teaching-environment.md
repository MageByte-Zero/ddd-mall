# ADR-02：Seata 在教学环境的部署形态与版本锁定

- 状态：Accepted
- 日期：2026-09-05
- 决策人：专栏作者
- 关联讲次：第 10 讲（首次引入），影响第 11/15/24 讲的全局事务场景
- 关联夹具：`docker-compose/docker-compose.yml`、`docker-compose/seata/application.yml`、
  `fixtures/incidents/seata-rollback-failed.md`

## 背景与约束

第 10 讲引入 Seata AT：mall-order（TM+RM）经 Feign 调 mall-inventory（RM），
TC 以容器运行。教学网络形态有一个硬约束：

- 4 个业务应用以 `java -jar` 跑在**宿主机**，中间件跑在 **Colima 的 docker 容器**；
- 容器注册到 Nacos 的默认地址是 docker bridge IP（实测 172.18.0.4），
  **宿主机进程无法路由到该地址**（与 L8 修 `brokerIP1` 同源的坑）；
- TC（seata-server）的 `NacosRegistryServiceImpl.register` 直接上报本机探测地址，
  2.2.0 服务端没有"指定注册 IP"的配置项；官方镜像入口脚本（2.1/2.2 的
  `/seata-server-entrypoint.sh`）也不支持旧版 `SEATA_IP` 环境变量。

同时，版本侧有一个已核验的事实：

- Docker Hub `apache/seata-server:2.1.0` 标签内 `libs/` 实际是
  **seata-*-2.0.0.jar**（镜像内文件核验 + 注册握手日志 `server version:2.0.0`）；
- spring-cloud-alibaba 2023.0.1.3 BOM 自带客户端
  `org.apache.seata:seata-spring-boot-starter:2.1.0`。

## 候选方案

### 方案 A：客户端走 Nacos 注册中心发现 TC（生产主流形态）

- 描述：`seata.registry.type=nacos`，客户端订阅 `SEATA_GROUP@@seata-server`
  拿 TC 地址。
- 优点：与生产部署一致；TC 扩缩容/漂移无感知。
- 缺点：本环境注册的地址是容器内 bridge IP，宿主机上的应用连不上；
  解决它要么把应用也搬进容器（教学阶段失去 `java -jar` 直接观测的便利），
  要么给服务端打"伪造注册地址"的补丁（非主流，引入自创写法）。

### 方案 B：客户端 file 注册 + 静态地址列表（grouplist）✅ 采用

- 描述：`seata.registry.type=file` +
  `seata.service.grouplist.default=127.0.0.1:8091`（compose 已发布端口），
  `vgroup-mapping` 把 `ddd-mall-tx-group` 映射到 `default` 集群。
  服务端仍注册进 Nacos（供控制台与后续讲次观测），但客户端不依赖它寻址。
- 优点：零额外中间件、零自创补丁，是 Seata 官方文件配置的合法形态；
  宿主机经发布端口直连，与教学网络形态完全匹配；行为确定，排错面小。
- 缺点：TC 地址写死在配置里，多 TC/漂移需要改配置——教学环境单实例，不构成问题。

### 方案 C：把 4 个应用容器化后再用方案 A

- 描述：全链路进 compose，Nacos 注册体系内自洽。
- 缺点：第 10 讲的教学目标是分布式事务机制，不是部署形态；容器化把
  `logs/*.out` 直接观测、断点调试的便利让渡出去，且后续讲次（网关/链路）
  仍需要宿主机运行形态。留作课程收尾的可选加餐，不阻塞本讲。

## 版本决策

客户端与服务端统一锁 **2.2.0**：

- 服务端：`apache/seata-server:2.2.0`（镜像内 `seata-*-2.2.0.jar` 核验）。
  不用 `2.1.0` 标签——其镜像内容与版本号不符（实为 2.0.0）。
- 客户端：父 pom `seata-spring-boot.version=2.2.0` 覆盖 SCA BOM 的 2.1.0，
  并在 dependencyManagement 显式管理，防止 BOM 升级悄悄带走版本。
- 注册握手实测：`client version:2.2.0` 与 2.2.0 服务端全链路成功
  （Begin/Register/Commit/Rollback 四类消息，见第 10 讲证据记录）。
- 服务端握手自报 `server version:2.0.0` 是协议上报字段滞后（镜像内
  jar 与 `pom.properties` 均为 2.2.0），以文件核验为准。

## 服务端配置两个坑（已修，随本 ADR 留档）

1. `console.user.username/password`：seata-server 2.x 控制台模块启动强依赖；
   挂载的 `application.yml` 覆盖了 jar 内默认配置就必须显式给出，否则
   `CustomUserDetailsServiceImpl` 占位符解析失败、容器重启循环（第 0 讲遗留问题）。
2. `seata.security.secretKey`（JWT）：同为强依赖占位符，缺了同样起不来。

## 可逆性

- 回到方案 A：两个应用 `application.yml` 把 `registry.type` 改回 `nacos`、
  删除 `grouplist` 两行；应用代码零改动（TM/RM 行为与寻址方式解耦）。
- 版本升级：改父 pom 属性 + compose 镜像 tag，两处各一行。
- 第 20 讲引入 Nacos 配置中心时，`vgroup-mapping`/`grouplist` 可平移进
  配置中心管理，与 `registry.type=file` 并存不冲突。
