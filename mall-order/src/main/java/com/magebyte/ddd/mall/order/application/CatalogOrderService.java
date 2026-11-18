package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.CatalogLookup;
import com.magebyte.ddd.mall.order.domain.CatalogUnavailableException;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import org.springframework.stereotype.Service;

@Service
public class CatalogOrderService {
    private final CatalogLookup catalog;
    private final OrderApplicationService orders;

    public CatalogOrderService(CatalogLookup catalog, OrderApplicationService orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    public OrderDetail create(CreateOrderCommand request) {
        var sku = catalog.find(request.skuId()).orElseThrow(() -> new OrderDomainException("SKU 不存在"));
        if (!request.skuId().equals(sku.skuId()) || sku.productId() == null || sku.productId() <= 0
                || sku.name() == null || sku.name().isBlank() || sku.name().length() > 128
                || sku.unitPrice() == null || sku.unitPrice().signum() <= 0 || sku.enabled() == null) {
            throw new CatalogUnavailableException("商品目录返回了无效的快照，请稍后重试");
        }
        if (!sku.enabled()) throw new OrderDomainException("SKU 已停用");
        if (request.productId() != null && !request.productId().equals(sku.productId())) {
            throw new OrderDomainException("SKU 不属于请求中的商品");
        }

        Money price = Money.of(sku.unitPrice());
        if (request.unitPrice() != null && !Money.of(request.unitPrice()).equals(price)) {
            throw new OrderDomainException("商品单价已变化，请刷新后重新下单");
        }
        if (price.multiply(request.quantity()).amount().precision() > 18) {
            throw new OrderDomainException("订单金额超出允许范围");
        }

        // Call the existing transactional bean only after obtaining an eligible catalogue snapshot.
        return orders.createOrder(new CreateOrderCommand(request.userId(), sku.productId(), sku.skuId(),
                sku.name(), request.quantity(), price.toString(), request.receiverName(),
                request.receiverPhone(), request.receiverAddress(), request.simulateRollbackFailure()));
    }
}
