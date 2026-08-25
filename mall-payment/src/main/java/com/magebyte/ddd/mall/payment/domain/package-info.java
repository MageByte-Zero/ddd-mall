/**
 * 领域层（Domain Layer）：支付（支撑域）业务规则的唯一居所，四层架构的核心。
 *
 * <p>存放聚合、实体、值对象、领域服务与仓储接口。
 * 依赖方向：不依赖接口层、应用层、基础设施层中的任何一层，
 * 也不绑定 Spring、MyBatis 等框架——业务规则只属于业务，不属于技术栈。
 * 仓储在这里只定义接口，实现由基础设施层通过依赖倒置提供。
 *
 * <p>四个限界上下文的四层包结构同构；层间依赖方向的自动守护见
 * mall-order 的 ArchitectureTest（ArchUnit）。
 */
package com.magebyte.ddd.mall.payment.domain;
