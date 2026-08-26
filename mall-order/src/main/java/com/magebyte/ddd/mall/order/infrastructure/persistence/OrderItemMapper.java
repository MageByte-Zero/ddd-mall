package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 订单项表 Mapper。订单项没有独立仓储——
 * 它是订单聚合的内部实体，只跟随聚合根一起存取。
 */
public interface OrderItemMapper extends BaseMapper<OrderItemDO> {
}
