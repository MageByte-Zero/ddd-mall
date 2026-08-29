/**
 * 接口层（Interfaces Layer）：订单（核心域）面向使用者的入口。
 *
 * <p>存放 REST 控制器、请求/响应 DTO 的组装，以及领域异常到 HTTP 状态码的
 * 统一翻译（GlobalExceptionHandler），负责协议与格式适配，不含业务规则。
 * 依赖方向：用例编排只经应用层（{@code ..application}），不越层直调仓储；
 * 领域层是四层最内层，其异常等值对象类型可被本层引用。
 *
 * <p>四个限界上下文的四层包结构同构；层间依赖方向的自动守护见
 * mall-order 的 ArchitectureTest（ArchUnit）。
 */
package com.magebyte.ddd.mall.order.interfaces;
