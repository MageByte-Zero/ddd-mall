package com.magebyte.ddd.mall.inventory.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.inventory.application.InventoryApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存接口层：库存扣减的 HTTP 入口。
 * 订单 BC 经 Feign 调用本接口；它同时是 Seata 全局事务在库存侧的入口
 * （请求头 TX_XID 由 Seata 的 MVC 拦截器自动绑定）。
 */
@RestController
@RequestMapping("/api/inventories")
public class InventoryController {

    private final InventoryApplicationService inventoryApplicationService;

    public InventoryController(InventoryApplicationService inventoryApplicationService) {
        this.inventoryApplicationService = inventoryApplicationService;
    }

    /**
     * 扣减库存。成功返回 200 + 统一响应体；库存不足等业务失败返回 422。
     */
    @PostMapping("/deductions")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> deduct(@Valid @RequestBody DeductInventoryRequest request) {
        inventoryApplicationService.deduct(request.skuCode(), request.quantity());
        return Result.ok();
    }
}
