package com.magebyte.ddd.mall.order.domain;

/**
 * 库存扣减端口：订单 BC 对"库存"这个支撑域能力的出口抽象。
 *
 * <p>依赖倒置：端口住在领域层（纯 Java 接口、零框架依赖），
 * 实现住在基础设施层（Feign 调库存 BC）。订单应用服务只依赖本接口，
 * 不 import Feign、不知道 HTTP。第 17/19 讲引入防腐层时，
 * 跨 BC 的身份翻译、字段隔离都在基础设施层适配器里加厚，本端口不动。
 *
 * <p>Seata AT 全局事务下，本方法在 {@code @GlobalTransactional} 用例内被调用，
 * XID 随 HTTP 头传播到库存 BC，库存侧的本地扣减作为分支事务参与同一全局事务。
 */
public interface InventoryDeductionPort {

    /**
     * 扣减指定 SKU 的可售库存。
     *
     * @param skuCode  SKU 编码（库存 BC 侧的身份）
     * @param quantity 扣减数量（正数）
     * @throws OrderDomainException 库存侧业务失败（库存不足等）时抛出，
     *                              由用例上的全局事务捕获并触发全局回滚
     */
    void deduct(String skuCode, int quantity);
}
