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

/**
 * 库存接口层：库存四个用例的 HTTP 入口。
 *
 * <p>订单 BC 经 Feign 调用 {@code /deductions} 与 {@code /releases}；这两个端点同时是
 * Seata 全局事务在库存侧的入口（请求头 TX_XID 由 Seata 的 MVC 拦截器自动绑定）。
 *
 * <p><b>关于 {@code /deductions} 这个名字。</b>它做的是<b>预占</b>，不是扣减。
 * 路径沿用第 10 讲建立的跨 BC 契约，本讲只改语义不改路径——跨 BC 端点一旦发布就是
 * 别人的集成基线，为了一个更贴切的名字去打断它不划算。第 13 讲引入幂等键时会连同
 * 请求体一起把路径正名为 {@code /api/inventories/reservations}，那次改名有实打实的
 * 收益（幂等键要进请求体，反正是破坏性变更）。
 */
@RestController
@RequestMapping("/api/inventories")
public class InventoryController {

    private final InventoryApplicationService inventoryApplicationService;

    public InventoryController(InventoryApplicationService inventoryApplicationService) {
        this.inventoryApplicationService = inventoryApplicationService;
    }

    /**
     * 预占库存（订单创建方向）。成功 200；库存不足等业务失败 422。
     */
    @PostMapping("/deductions")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> reserve(@Valid @RequestBody DeductInventoryRequest request) {
        inventoryApplicationService.reserve(request.skuCode(), request.quantity());
        return Result.ok();
    }

    /**
     * 释放预占（订单取消 / 超时未支付方向）。成功 200；
     * 释放量超过已预占 422；版本冲突 409。
     */
    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> release(@Valid @RequestBody ReleaseInventoryRequest request) {
        inventoryApplicationService.release(request.skuCode(), request.quantity());
        return Result.ok();
    }

    /**
     * 确认出库：已预占的货真正发走，总库存随之减少。成功 200；
     * 出库量超过已预占 422；版本冲突 409。
     */
    @PostMapping("/confirmations")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> confirm(@Valid @RequestBody ConfirmInventoryRequest request) {
        inventoryApplicationService.confirm(request.skuCode(), request.quantity());
        return Result.ok();
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
