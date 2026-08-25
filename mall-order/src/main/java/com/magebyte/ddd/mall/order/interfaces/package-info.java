/**
 * 接口层（Interfaces Layer）：订单（核心域）面向使用者的入口。
 *
 * <p>存放 REST 控制器、请求/响应 DTO 的组装，负责协议与格式适配，不含业务规则。
 * 依赖方向：只依赖应用层（{@code ..application}），不越层直调领域对象。
 *
 * <p>四个限界上下文的四层包结构同构；层间依赖方向的自动守护见
 * mall-order 的 ArchitectureTest（ArchUnit）。
 */
package com.magebyte.ddd.mall.order.interfaces;
