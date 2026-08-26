package com.magebyte.ddd.mall.order.domain;

import java.util.Optional;

/**
 * 订单仓储（Repository）接口。
 *
 * <p>仓储是"集合根视角的持久化抽象"：站在聚合根的角度存取整个订单聚合，
 * 而不是逐表读写。接口定义在领域层，实现由基础设施层通过依赖倒置提供——
 * 领域层因此不需要知道 MyBatis、JDBC 或任何数据库的存在。
 *
 * <p>一个聚合对应一个仓储；订单聚合的持久化只有这一个入口。
 */
public interface OrderRepository {

    /**
     * 保存订单聚合：新聚合（id 为空）执行插入，已有聚合执行更新。
     * 返回持久化后的聚合（带数据库回填的 id 与最新版本号）。
     */
    Order save(Order order);

    Optional<Order> findById(Long id);

    Optional<Order> findByOrderNo(String orderNo);
}
