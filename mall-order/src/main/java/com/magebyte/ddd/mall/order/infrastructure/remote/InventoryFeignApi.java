package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.commons.response.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 库存 BC 的 Feign 客户端：只描述 HTTP 协议（服务名、路径、出入参），
 * 不做任何业务判断。全局事务 XID 的请求头由 Seata Feign 配置
 * （{@code SeataFeignConfig}）统一注入。
 */
@FeignClient(name = "mall-inventory", contextId = "inventoryClient")
public interface InventoryFeignApi {

    /**
     * 扣减库存。库存不足等业务失败返回 HTTP 422，Feign 抛 FeignException。
     */
    @PostMapping(value = "/api/inventories/deductions",
            consumes = "application/json", produces = "application/json")
    Result<Void> deduct(@RequestBody InventoryDeductionRequest request);
}
