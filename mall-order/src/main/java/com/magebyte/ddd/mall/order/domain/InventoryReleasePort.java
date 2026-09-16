package com.magebyte.ddd.mall.order.domain;

/**
 * 库存归还端口：订单 BC 对"库存回补"能力的出口抽象（第 11 讲随取消用例引入）。
 *
 * <p>和 {@link InventoryDeductionPort} 是同一个战术动作的两个方向：下单时
 * 调 {@code deduct} 把库存划走，取消时调本端口把它还回去。两个端口分开定义，
 * 是因为它们在业务上属于不同的用例——扣减属于"创建订单"，归还属于"取消订单"，
 * 领域语言里也没有"把扣减传个负数"这种说法。
 *
 * <p>依赖倒置的落点与扣减端口完全一致：接口住领域层、零框架依赖，
 * Feign 适配器住基础设施层。取消用例把它和订单状态的改写放进同一个
 * AT 全局事务，因此"订单已取消但库存没还"这个错位不可能出现。
 */
public interface InventoryReleasePort {

    /**
     * 归还指定 SKU 的可售库存。
     *
     * @param skuCode  SKU 编码（库存 BC 侧的身份）
     * @param quantity 归还数量（正数）
     * @throws OrderDomainException 库存侧业务失败（重复释放、SKU 不存在等）时抛出，
     *                              由取消用例上的全局事务捕获并触发全局回滚
     */
    void release(String skuCode, int quantity);
}
