package com.magebyte.ddd.mall.order.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * Outbox 事件表 Mapper。
 *
 * <p>单表 CRUD 由 MyBatis-Plus 提供；"捞一批待投递事件"的查询语义
 * 由中继器（{@code infrastructure.messaging.OutboxEventRelay}）用
 * LambdaQueryWrapper 组装：status = PENDING 且退避已到期，按 id 升序
 * （id 自增即事件产生顺序），LIMIT 批量大小。
 */
public interface OutboxEventMapper extends BaseMapper<OutboxEventDO> {
}
