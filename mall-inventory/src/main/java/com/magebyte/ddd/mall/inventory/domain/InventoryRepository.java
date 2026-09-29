package com.magebyte.ddd.mall.inventory.domain;

import java.util.Optional;

/**
 * 库存仓储端口：领域层定义，基础设施层实现（依赖倒置）。
 *
 * <p><b>第 12 讲把写路径分成两条，这是本讲最核心的一个工程取舍。</b>
 *
 * <p>两条路径保护的不是同一件事：
 *
 * <ul>
 *   <li>{@link #save(Inventory)} —— <b>聚合整体保存 + 乐观锁</b>。读聚合 → 领域层改
 *       → 把整份状态写回去，SQL 带 {@code WHERE version = ?}，写不进去就是有人抢先改了。
 *       它保护的是<b>聚合整体的守恒</b>：一次操作要同时动可售、已预占、总库存三个数，
 *       任何一个被并发写脏，守恒就碎了。这是《实现领域驱动设计》里推荐的正统写法
 *       ——聚合就是事务边界，版本冲突由应用服务决定重试还是放弃。</li>
 *   <li>{@link #reserveAtomically(String, int)} —— <b>原子条件更新</b>。一条
 *       {@code UPDATE ... SET available = available - n WHERE available >= n}
 *       把"库存不足"下沉成 SQL 的 WHERE。它保护的是<b>超卖这条硬底线</b>：
 *       不管多少个请求同时进来，可售库存不会被扣成负数。</li>
 * </ul>
 *
 * <p>为什么预占不走乐观锁？因为预占是库存系统里最热的那条路径——大促时同一个爆款
 * SKU 的行会被成千上万个请求盯着。乐观锁在这条路径上会把"并发度"直接兑换成"失败率"：
 * 几十个并发抢同一行，只有一个能写进去，其余全部版本冲突，重试后又撞在一起。
 * 而原子条件更新对同一行的并发是"能卖多少卖多少"，冲突代价只是一次行锁等待。
 * 本讲的并发实验（{@code InventoryConcurrencyTest}）会把这个差距实测出来。
 *
 * <p>反过来，释放预占 / 确认出库 / 补货都是低频操作，走聚合整体保存：它们的正确性依赖
 * 守恒，而守恒在领域层，只有把整份状态写回去，才能让"领域层算出来的结果"和
 * "数据库里的结果"是同一个结果。
 */
public interface InventoryRepository {

    /** 按 SKU 编码查找库存聚合。 */
    Optional<Inventory> findBySkuCode(String skuCode);

    /**
     * 聚合整体保存 + 乐观锁：把领域层算好的三个数字一次性写回去。
     *
     * <p>持久化层把 {@link Inventory#version()} 作为 {@code WHERE version = ?} 的条件，
     * 命中则同时自增版本号。返回 0 行意味着"你读到的那一版已经不是最新一版"，
     * 由调用方（应用服务）决定重试还是抛 {@link InventoryConcurrencyException}。
     *
     * @return 实际影响行数（0 = 版本冲突，1 = 保存成功）
     */
    int save(Inventory inventory);

    /**
     * 追加一条库存流水。由应用服务在一次变动成功之后调用，前后两张快照都满足守恒。
     *
     * <p>放在仓储而不是聚合里，是因为流水是审计需求而不是一致性需求：
     * 聚合要守住的是"现在这三个数对不对"，流水要记的是"历史上挪过哪几步"。
     */
    void appendLog(String skuCode, String changeType, int quantity,
                   StockSnapshot before, StockSnapshot after);

    /**
     * 原子预占：一条 SQL 完成"可售减、已预占加"，且仅当可售库存足够时命中。
     *
     * <p>它是预占路径的最后一道防线。领域层的 {@link Inventory#reserve(int)} 已经
     * 用读到的快照做过一次守卫，但那次守卫挡不住"读到时还够、写入时已被抢走"的窗口，
     * 这条 WHERE 挡的是这个窗口。
     *
     * @return 实际影响行数（0 = 库存不足或并发冲突，1 = 预占成功）
     */
    int reserveAtomically(String skuCode, int quantity);
}
