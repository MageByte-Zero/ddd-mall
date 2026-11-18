package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.order.domain.CatalogLookup.Sku;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "mall-product", contextId = "catalogClient", url = "${ddd.order.catalog-base-url:}")
public interface CatalogFeignApi {
    @GetMapping("/api/catalog/skus/{skuId}")
    Sku get(@PathVariable("skuId") long skuId);
}
