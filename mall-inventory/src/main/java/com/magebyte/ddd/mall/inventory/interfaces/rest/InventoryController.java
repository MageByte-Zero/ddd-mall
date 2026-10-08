package com.magebyte.ddd.mall.inventory.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.inventory.application.InventoryApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** L13：新预占/释放/出库必须带业务身份；旧扣减路径保留但拒绝写入。 */
@RestController
@RequestMapping("/api/inventories")
public class InventoryController {

    private final InventoryApplicationService inventoryApplicationService;
    private final com.magebyte.ddd.mall.inventory.application.IdempotentInventoryService idempotent;

    public InventoryController(InventoryApplicationService inventoryApplicationService,
            com.magebyte.ddd.mall.inventory.application.IdempotentInventoryService idempotent) {
        this.idempotent = idempotent;
        this.inventoryApplicationService = inventoryApplicationService;
    }

    /**
     * 预占库存（订单创建方向）。成功 200；库存不足等业务失败 422。
     */
    @Deprecated
    @PostMapping("/deductions")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> reserve(@Valid @RequestBody DeductInventoryRequest request) {
        throw new com.magebyte.ddd.mall.inventory.domain.InventoryDomainException("旧扣减入口已废弃，请使用带业务身份的 /reservations");
    }

    @PostMapping("/reservations")
    public Result<com.magebyte.ddd.mall.inventory.domain.InventoryOperationResult> reserveIdentified(@Valid @RequestBody InventoryOperationRequest request) {
        return Result.ok(idempotent.reserve(request.toCommand()));
    }

    /**
     * 释放预占（订单取消 / 超时未支付方向）。成功 200；
     * 释放量超过已预占 422；版本冲突 409。
     */
    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.OK)
    public Result<com.magebyte.ddd.mall.inventory.domain.InventoryOperationResult> release(@Valid @RequestBody InventoryOperationRequest request) {
        return Result.ok(idempotent.release(request.toCommand()));
    }

    /**
     * 确认出库：已预占的货真正发走，总库存随之减少。成功 200；
     * 出库量超过已预占 422；版本冲突 409。
     */
    @PostMapping("/confirmations")
    @ResponseStatus(HttpStatus.OK)
    public Result<com.magebyte.ddd.mall.inventory.domain.InventoryOperationResult> confirm(@Valid @RequestBody InventoryOperationRequest request) {
        return Result.ok(idempotent.confirm(request.toCommand()));
    }

    /**
     * 补货入库。成功 200；数量非法 422；版本冲突 409。
     */
    @PostMapping("/restocks")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> restock(@Valid @RequestBody RestockInventoryRequest request) {
        inventoryApplicationService.restock(request.skuCode(), request.quantity());
        return Result.ok();
    }

    /**
     * 查询库存三件套。库存记录不存在返回 422（沿用本 BC 的领域异常语义）。
     */
    @GetMapping("/{skuCode}")
    @ResponseStatus(HttpStatus.OK)
    public Result<InventoryResponse> get(@PathVariable String skuCode) {
        return Result.ok(InventoryResponse.from(inventoryApplicationService.get(skuCode)));
    }
}
