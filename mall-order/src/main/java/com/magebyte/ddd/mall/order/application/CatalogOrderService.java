package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.CatalogLookup;
import com.magebyte.ddd.mall.order.domain.CatalogUnavailableException;
import com.magebyte.ddd.mall.order.domain.Money;
import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
public class CatalogOrderService {
    private final CatalogLookup catalog;
    private final OrderApplicationService orders;

    public CatalogOrderService(CatalogLookup catalog, OrderApplicationService orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    public OrderDetail create(CreateOrderCommand request) {
        var snapshots = new HashMap<Long, CatalogLookup.Sku>();
        var lines = request.lines().stream().map(line -> snapshot(line,
                snapshots.computeIfAbsent(line.skuId(), this::findPurchasableSku))).toList();

        // 目录读取在 AT 事务之前。同一 SKU 在本次下单只读取一次。
        return orders.createOrder(new CreateOrderCommand(request.userId(), lines, request.receiverName(),
                request.receiverPhone(), request.receiverAddress(), request.simulateRollbackFailure()));
    }

    private CatalogLookup.Sku findPurchasableSku(long skuId) {
        var sku = catalog.find(skuId).orElseThrow(() -> new OrderDomainException("SKU 不存在"));
        if (!Long.valueOf(skuId).equals(sku.skuId()) || sku.productId() == null || sku.productId() <= 0
                || sku.name() == null || sku.name().isBlank() || sku.name().length() > 128
                || sku.unitPrice() == null || sku.unitPrice().signum() <= 0 || sku.enabled() == null) {
            throw new CatalogUnavailableException("商品目录返回了无效的快照，请稍后重试");
        }
        if (!sku.enabled()) throw new OrderDomainException("SKU 已停用");
        return sku;
    }

    private static CreateOrderCommand.Line snapshot(CreateOrderCommand.Line line, CatalogLookup.Sku sku) {
        if (line.productId() != null && !line.productId().equals(sku.productId())) {
            throw new OrderDomainException("SKU 不属于请求中的商品");
        }

        Money price = Money.of(sku.unitPrice());
        if (line.unitPrice() != null && !Money.of(line.unitPrice()).equals(price)) {
            throw new OrderDomainException("商品单价已变化，请刷新后重新下单");
        }
        return new CreateOrderCommand.Line(sku.productId(), sku.skuId(), sku.name(),
                line.quantity(), price.toString(), line.purchaseRequestNo());
    }
}
