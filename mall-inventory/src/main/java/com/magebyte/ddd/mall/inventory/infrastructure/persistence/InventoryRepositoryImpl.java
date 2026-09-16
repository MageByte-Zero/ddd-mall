package com.magebyte.ddd.mall.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.magebyte.ddd.mall.inventory.domain.Inventory;
import com.magebyte.ddd.mall.inventory.domain.InventoryRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 库存仓储的基础设施层实现：依赖倒置的落点（领域层定义
 * {@link InventoryRepository}，本类实现它并持有 Mapper）。
 */
@Repository
public class InventoryRepositoryImpl implements InventoryRepository {

    private final InventoryMapper inventoryMapper;

    public InventoryRepositoryImpl(InventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    @Override
    public Optional<Inventory> findBySkuCode(String skuCode) {
        InventoryDO inventoryDO = inventoryMapper.selectOne(
                Wrappers.<InventoryDO>lambdaQuery().eq(InventoryDO::getSkuCode, skuCode));
        if (inventoryDO == null) {
            return Optional.empty();
        }
        return Optional.of(Inventory.reconstitute(
                inventoryDO.getId(),
                inventoryDO.getSkuCode(),
                inventoryDO.getSkuName(),
                inventoryDO.getTotalStock(),
                inventoryDO.getAvailableStock(),
                inventoryDO.getCreatedAt(),
                inventoryDO.getUpdatedAt()));
    }

    @Override
    public int deduct(String skuCode, int quantity) {
        // 条件 UPDATE：把"库存不足"下沉成一条 SQL 的 WHERE 条件，
        // 并发下也不会把可售库存扣成负数（乐观锁防超卖在库存预占讲次再加）。
        return inventoryMapper.update(null,
                Wrappers.<InventoryDO>lambdaUpdate()
                        .setSql("available_stock = available_stock - " + quantity)
                        .eq(InventoryDO::getSkuCode, skuCode)
                        .ge(InventoryDO::getAvailableStock, quantity));
    }

    @Override
    public int release(String skuCode, int quantity) {
        // 归还方向的护栏同样下沉到 SQL：只有"还完之后不超过总库存"才命中。
        // 领域守卫（Inventory#release）拦的是单线程内的越界，这条 WHERE
        // 拦的是两个取消请求同时进来时的并发越界——两者缺一不可。
        return inventoryMapper.update(null,
                Wrappers.<InventoryDO>lambdaUpdate()
                        .setSql("available_stock = available_stock + " + quantity)
                        .eq(InventoryDO::getSkuCode, skuCode)
                        .apply("available_stock + " + quantity + " <= total_stock"));
    }
}
