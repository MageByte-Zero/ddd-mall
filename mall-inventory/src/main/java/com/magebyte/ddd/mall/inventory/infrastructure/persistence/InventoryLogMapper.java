package com.magebyte.ddd.mall.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 库存流水映射器。流水只增不改不删，随库存写入的同一个本地事务落库——
 * 库存改了却没记流水，和库存没改却记了流水，一样是审计事故。
 */
public interface InventoryLogMapper extends BaseMapper<InventoryLogDO> {
}
