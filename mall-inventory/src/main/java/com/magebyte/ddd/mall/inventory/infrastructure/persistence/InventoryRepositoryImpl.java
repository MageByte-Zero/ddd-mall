package com.magebyte.ddd.mall.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.magebyte.ddd.mall.inventory.domain.Inventory;
import com.magebyte.ddd.mall.inventory.domain.InventoryRepository;
import com.magebyte.ddd.mall.inventory.domain.StockSnapshot;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 库存仓储的基础设施层实现：依赖倒置的落点（领域层定义
 * {@link InventoryRepository}，本类实现它并持有 Mapper）。
 *
 * <p>第 12 讲在这里落地两条写路径，SQL 形态完全不同，不要混淆：
 *
 * <ol>
 *   <li>{@link #save(Inventory)}：{@code UPDATE ... WHERE id = ? AND version = ?}，
 *       版本条件由 MyBatis-Plus 的乐观锁拦截器自动补上并自增。
 *       它是一次"读-改-写"的收口，写不进去就说明有人抢先改过。</li>
 *   <li>{@link #reserveAtomically(String, int)}：
 *       {@code UPDATE ... SET available = available - n, reserved = reserved + n
 *       WHERE sku_code = ? AND available >= n}。
 *       没有版本条件——它不需要：{@code available >= n} 在 MySQL 的行锁下天然原子，
 *       两个事务不可能同时看到"还剩最后 1 件"又都扣成功。</li>
 * </ol>
 */
@Repository
public class InventoryRepositoryImpl implements InventoryRepository {

    private final InventoryMapper inventoryMapper;
    private final InventoryLogMapper inventoryLogMapper;

    public InventoryRepositoryImpl(InventoryMapper inventoryMapper,
                                   InventoryLogMapper inventoryLogMapper) {
        this.inventoryMapper = inventoryMapper;
        this.inventoryLogMapper = inventoryLogMapper;
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
                inventoryDO.getReservedStock(),
                inventoryDO.getVersion(),
                inventoryDO.getCreatedAt(),
                inventoryDO.getUpdatedAt()));
    }

    @Override
    public int save(Inventory inventory) {
        InventoryDO dataObject = new InventoryDO();
        dataObject.setId(inventory.id());
        dataObject.setSkuCode(inventory.skuCode());
        dataObject.setSkuName(inventory.skuName());
        dataObject.setTotalStock(inventory.totalStock());
        dataObject.setAvailableStock(inventory.availableStock());
        dataObject.setReservedStock(inventory.reservedStock());
        // 版本号：乐观锁拦截器把它拼成 WHERE version = ? 并自增为 version + 1。
        // 它就是"我读到的那一版还是不是最新一版"这个问题的数据库表达。
        dataObject.setVersion(inventory.version());
        // updateById 遇到 @Version 字段时由拦截器改写 SQL；影响 0 行 = 版本冲突。
        return inventoryMapper.updateById(dataObject);
    }

    @Override
    public void appendLog(String skuCode, String changeType, int quantity,
                          StockSnapshot before, StockSnapshot after) {
        inventoryLogMapper.insert(InventoryLogDO.of(
                skuCode, changeType, quantity,
                before.totalStock(), after.totalStock(),
                before.availableStock(), after.availableStock(),
                before.reservedStock(), after.reservedStock()));
    }

    @Override
    public int reserveAtomically(String skuCode, int quantity) {
        // 一条 SQL 同时挪两个数：可售减、已预占加。
        // WHERE 里的 available_stock >= n 才是防超卖的那道墙——领域层用读到的快照
        // 拦的是"我看到的库存够不够"，这一句拦的是"我写进去的那一刻还够不够"。
        return inventoryMapper.update(null,
                Wrappers.<InventoryDO>lambdaUpdate()
                        .setSql("available_stock = available_stock - " + quantity)
                        .setSql("reserved_stock = reserved_stock + " + quantity)
                        .eq(InventoryDO::getSkuCode, skuCode)
                        .ge(InventoryDO::getAvailableStock, quantity));
    }
}
