package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 订单表 Mapper。MyBatis-Plus 提供单表 CRUD；
 * 聚合级别的存取语义由 {@link OrderRepositoryImpl} 封装。
 */
public interface OrderMapper extends BaseMapper<OrderDO> {
}
