/**
 * 基础设施层（Infrastructure Layer）：订单（核心域）的技术细节实现。
 *
 * <p>存放 MyBatis Mapper、仓储接口的实现与外部服务调用。
 * 依赖方向：依赖领域层（{@code ..domain}），通过依赖倒置实现领域层定义的仓储接口；
 * 不依赖接口层与应用层。
 *
 * <p>四个限界上下文的四层包结构同构；层间依赖方向的自动守护见
 * mall-order 的 ArchitectureTest（ArchUnit）。
 */
package com.magebyte.ddd.mall.order.infrastructure;
