package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.order.domain.InventoryReleasePort;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * 库存归还端口的 Feign 实现：把领域语言（归还库存）翻译成库存 BC 的 HTTP 协议。
 *
 * <p>与 {@link FeignInventoryDeductionAdapter} 同款结构，错误翻译策略也一致：
 * 不透吞掉库存侧的 422 错误体。取消用例里这条链路一旦失败，
 * 异常会冒到 {@code @GlobalTransactional} 上，订单状态连同回滚回 PENDING_PAY——
 * 宁可让用户看到"取消失败稍后再试"，也不能出现"订单已取消、库存没还"的对账缺口。
 */
@Component
public class FeignInventoryReleaseAdapter implements InventoryReleasePort {

    private final InventoryFeignApi inventoryFeignApi;

    public FeignInventoryReleaseAdapter(InventoryFeignApi inventoryFeignApi) {
        this.inventoryFeignApi = inventoryFeignApi;
    }

    @Override
    public void release(String skuCode, int quantity) {
        try {
            inventoryFeignApi.release(new InventoryReleaseRequest(skuCode, quantity));
        } catch (FeignException ex) {
            String detail = ex.contentUTF8();
            throw new OrderDomainException("库存服务归还失败（HTTP " + ex.status()
                    + "），全局事务将回滚：" + detail, ex);
        }
    }
}
