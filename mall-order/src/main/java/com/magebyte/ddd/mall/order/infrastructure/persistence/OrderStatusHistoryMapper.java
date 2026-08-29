package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 订单状态历史表 Mapper。状态历史没有独立仓储——它是订单聚合的内部记录，
 * 随 {@link OrderRepositoryImpl} 的聚合存取一起写入和读出。
 */
public interface OrderStatusHistoryMapper extends BaseMapper<OrderStatusHistoryDO> {
}
