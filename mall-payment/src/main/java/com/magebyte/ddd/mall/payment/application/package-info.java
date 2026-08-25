/**
 * 应用层（Application Layer）：支付（支撑域）的用例编排层，保持薄。
 *
 * <p>存放应用服务：接收请求、控制事务边界、按顺序调用领域对象完成用例；
 * 业务规则不写在这一层。
 * 依赖方向：只依赖领域层（{@code ..domain}），不依赖接口层与基础设施层。
 *
 * <p>四个限界上下文的四层包结构同构；层间依赖方向的自动守护见
 * mall-order 的 ArchitectureTest（ArchUnit）。
 */
package com.magebyte.ddd.mall.payment.application;
