package com.magebyte.ddd.mall.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存表 MyBatis-Plus Mapper。
 */
@Mapper
public interface InventoryMapper extends BaseMapper<InventoryDO> {
}
