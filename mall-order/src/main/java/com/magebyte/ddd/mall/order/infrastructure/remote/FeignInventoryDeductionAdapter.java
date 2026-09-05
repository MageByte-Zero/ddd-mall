package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.order.domain.InventoryDeductionPort;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * 库存扣减端口的 Feign 实现：把领域语言（扣减库存）翻译成
 * 库存 BC 的 HTTP 协议，跨 BC 报错统一翻译成订单领域异常。
 *
 * <p>本类是订单 BC 的跨 BC 边界点：第 17/19 讲的防腐层就在这层加厚
 * （第三方身份、字段、错误码的隔离）。库存不足（HTTP 422）时库存侧
 * 本地事务已经回滚，这里抛出领域异常让订单侧用例失败，
 * Seata 随即对订单分支发起全局回滚。
 */
@Component
public class FeignInventoryDeductionAdapter implements InventoryDeductionPort {

    private final InventoryFeignApi inventoryFeignApi;

    public FeignInventoryDeductionAdapter(InventoryFeignApi inventoryFeignApi) {
        this.inventoryFeignApi = inventoryFeignApi;
    }

    @Override
    public void deduct(String skuCode, int quantity) {
        try {
            inventoryFeignApi.deduct(new InventoryDeductionRequest(skuCode, quantity));
        } catch (FeignException ex) {
            // 不吞掉错误体：422 响应里带着库存侧的失败原因，直接透传给用例/读者
            String detail = ex.contentUTF8();
            throw new OrderDomainException("库存服务扣减失败（HTTP " + ex.status()
                    + "），全局事务将回滚：" + detail, ex);
        }
    }
}
