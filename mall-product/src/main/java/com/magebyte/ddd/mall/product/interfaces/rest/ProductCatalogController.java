package com.magebyte.ddd.mall.product.interfaces.rest;

import com.magebyte.ddd.mall.product.application.ProductCatalogService;
import com.magebyte.ddd.mall.product.application.ProductCatalogService.Sku;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/catalog/skus")
public class ProductCatalogController {
    private final ProductCatalogService catalog;

    public ProductCatalogController(ProductCatalogService catalog) {
        this.catalog = catalog;
    }

    public record CreateSku(Long skuId, Long productId, String name, BigDecimal unitPrice, Boolean enabled) {}
    public record UpdateSku(String name, BigDecimal unitPrice, Boolean enabled) {}
    public record CatalogError(String message) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Sku create(@RequestBody CreateSku request) {
        return catalog.create(request.skuId(), request.productId(), request.name(), request.unitPrice(), request.enabled());
    }

    @PutMapping("/{skuId}")
    public Sku update(@PathVariable long skuId, @RequestBody UpdateSku request) {
        return require(catalog.update(skuId, request.name(), request.unitPrice(), request.enabled()));
    }

    @GetMapping("/{skuId}")
    public Sku get(@PathVariable long skuId) {
        return require(catalog.find(skuId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public CatalogError invalidSku(IllegalArgumentException e) {
        return new CatalogError(e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CatalogError duplicateSku() {
        return new CatalogError("商品 ID 或 SKU ID 已存在");
    }

    private static Sku require(Optional<Sku> sku) {
        return sku.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU 不存在"));
    }
}
