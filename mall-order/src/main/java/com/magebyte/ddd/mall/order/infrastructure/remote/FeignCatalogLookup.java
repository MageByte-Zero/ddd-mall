package com.magebyte.ddd.mall.order.infrastructure.remote;

import com.magebyte.ddd.mall.order.domain.CatalogLookup;
import com.magebyte.ddd.mall.order.domain.CatalogUnavailableException;
import feign.FeignException;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class FeignCatalogLookup implements CatalogLookup {
    private final CatalogFeignApi api;

    public FeignCatalogLookup(CatalogFeignApi api) {
        this.api = api;
    }

    @Override
    public Optional<Sku> find(long skuId) {
        try {
            Sku snapshot = api.get(skuId);
            if (snapshot == null) throw new CatalogUnavailableException("商品目录响应为空，请稍后重试");
            return Optional.of(snapshot);
        } catch (FeignException.NotFound e) {
            return Optional.empty();
        } catch (FeignException e) {
            throw new CatalogUnavailableException("商品目录暂不可用，请稍后重试", e);
        }
    }
}
