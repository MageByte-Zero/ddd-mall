/**
 * mall-commons 公共模块：四个限界上下文共享的、业务无关的横切公共代码。
 *
 * <p>本模块只放不含任何业务规则的公共件，当前有统一响应体
 * {@link com.magebyte.ddd.mall.commons.response.Result}（code/message/data）。
 * 约定：不依赖任何特定限界上下文的领域类型，可被各 BC 的接口层直接引用。
 */
package com.magebyte.ddd.mall.commons;
