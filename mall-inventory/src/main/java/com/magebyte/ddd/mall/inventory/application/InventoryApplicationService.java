package com.magebyte.ddd.mall.inventory.application;

import com.magebyte.ddd.mall.inventory.domain.Inventory;
import com.magebyte.ddd.mall.inventory.domain.InventoryConcurrencyException;
import com.magebyte.ddd.mall.inventory.domain.InventoryDomainException;
import com.magebyte.ddd.mall.inventory.domain.InventoryRepository;
import com.magebyte.ddd.mall.inventory.domain.StockSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存应用服务：用例编排，不含业务规则（规则在 {@link Inventory} 聚合里）。
 *
 * <p>四个用例，两条写路径，分工见下表——这张表是本讲全部代码的纲：
 *
 * <pre>
 *   预占 reserve    → 原子条件 UPDATE   热点路径，防超卖的硬底线
 *   释放 release    → 聚合整体保存+乐观锁  低频，正确性依赖守恒
 *   出库 confirm    → 聚合整体保存+乐观锁  低频，正确性依赖守恒
 *   补货 restock    → 聚合整体保存+乐观锁  低频，正确性依赖守恒
 * </pre>
 *
 * <p>{@code reserve} 是 Seata AT 分支事务的边界：订单 BC 的全局事务经 Feign 调到这里时，
 * 当前线程已绑定全局事务 XID（HTTP 头 TX_XID 传播），本方法上的 {@link Transactional}
 * 本地事务会被 Seata 数据源代理登记为一个分支事务——一阶段随本地提交上报 TC，
 * 二阶段由 TC 通知提交或回滚。
 *
 * <p><b>为什么乐观锁路径不做重试。</b>版本冲突时最直觉的写法是"重新读一次再写"，
 * 但在 MySQL 默认的可重复读（Repeatable Read）隔离级别下，同一个事务里第二次读
 * 拿到的是<b>同一个快照</b>——版本号根本不会变，重试多少次都是同一个结果。
 * 要重试就必须开一个新的事务（{@code REQUIRES_NEW}），而那又会把这段操作从当前的
 * AT 分支里摘出去，破坏"订单和库存同成同败"。所以这里的处理是<b>不重试，直接抛
 * {@link InventoryConcurrencyException}</b>，让调用方决定是重试整个用例还是放弃。
 * 本讲的并发实验会给出这个选择背后的数字。
 */
@Service
public class InventoryApplicationService {

    private final InventoryRepository inventoryRepository;

    public InventoryApplicationService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * 预占库存（订单创建方向）。
     *
     * <p>两道闸门，缺一不可：
     * <ol>
     *   <li>领域层 {@link Inventory#reserve(int)} 用读到的快照判断"够不够"，
     *       给出可读的业务错误（库存不足）；</li>
     *   <li>仓储层 {@link InventoryRepository#reserveAtomically(String, int)} 用
     *       {@code WHERE available_stock >= n} 判断"写入那一刻还够不够"，
     *       拦住并发窗口。第 10、11 讲只有这一条，第 12 讲把领域层补上了。</li>
     * </ol>
     */
    @Transactional
    @Deprecated // L13: 仅保留 L12 教学回归；新业务必须使用 IdempotentInventoryService
    public void reserve(String skuCode, int quantity) {
        Inventory inventory = requireInventory(skuCode);
        StockSnapshot before = inventory.snapshot();
        inventory.reserve(quantity);
        int affected = inventoryRepository.reserveAtomically(skuCode, quantity);
        if (affected == 0) {
            // 并发窗口：读到时还够、写入时已被抢走
            throw new InventoryDomainException("库存不足（并发预占冲突）: " + skuCode);
        }
        inventoryRepository.appendLog(skuCode, "RESERVE", quantity, before, inventory.snapshot());
    }

    /**
     * 释放预占（订单取消 / 超时未支付方向），AT 分支事务边界。
     *
     * <p>走聚合整体保存而不是原子 SQL，因为它要同时改可售和已预占两个数，
     * 而"释放量不得超过已预占"这个判断依赖领域层对整份状态的把握。
     * 版本冲突抛 {@link InventoryConcurrencyException}（409），
     * 由调用方决定重试整个用例还是放弃。
     */
    @Transactional
    @Deprecated // L13: 仅保留 L12 教学回归；新业务必须使用 IdempotentInventoryService
    public void release(String skuCode, int quantity) {
        Inventory inventory = requireInventory(skuCode);
        StockSnapshot before = inventory.snapshot();
        inventory.release(quantity);
        saveOrThrowConcurrent(inventory, skuCode, "释放预占");
        inventoryRepository.appendLog(skuCode, "RELEASE", quantity, before, inventory.snapshot());
    }

    /**
     * 确认出库：把已预占的货真正发走，总库存与已预占一起减少。
     *
     * <p>本讲交付这个能力并测透，但订单侧暂不调用——订单在哪个时点确认出库是业务决策，
     * 第 25 讲的事件总线会把"支付成功"或"发货"接到这里。留一个未接线的能力不是偷懒：
     * 预占模型下，已支付的订单继续占着库存是<b>正确</b>的，货还在仓库里没发走。
     */
    @Transactional
    @Deprecated // L13: 仅保留 L12 教学回归；新业务必须使用 IdempotentInventoryService
    public void confirm(String skuCode, int quantity) {
        Inventory inventory = requireInventory(skuCode);
        StockSnapshot before = inventory.snapshot();
        inventory.confirm(quantity);
        saveOrThrowConcurrent(inventory, skuCode, "确认出库");
        inventoryRepository.appendLog(skuCode, "CONFIRM", quantity, before, inventory.snapshot());
    }

    /**
     * 补货入库：总库存与可售一起增加。守恒式里唯一让等号右边变大的入口。
     */
    @Transactional
    public void restock(String skuCode, int quantity) {
        Inventory inventory = requireInventory(skuCode);
        StockSnapshot before = inventory.snapshot();
        inventory.restock(quantity);
        saveOrThrowConcurrent(inventory, skuCode, "补货入库");
        inventoryRepository.appendLog(skuCode, "RESTOCK", quantity, before, inventory.snapshot());
    }

    /** 查询库存三件套。只读用例不开写事务。 */
    @Transactional(readOnly = true)
    public InventoryView get(String skuCode) {
        return InventoryView.from(requireInventory(skuCode));
    }

    private Inventory requireInventory(String skuCode) {
        return inventoryRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new InventoryDomainException("库存记录不存在: " + skuCode));
    }

    /**
     * 乐观锁保存：写不进去就是有人抢先改过这一行。
     *
     * <p>注意这里没有"再试一次"。原因见类 Javadoc——同一个事务里重读拿到的是同一个快照，
     * 重试是自欺欺人。
     */
    private void saveOrThrowConcurrent(Inventory inventory, String skuCode, String action) {
        int affected = inventoryRepository.save(inventory);
        if (affected == 0) {
            throw new InventoryConcurrencyException(action + "遇到并发冲突（版本已变），未做任何修改: "
                    + skuCode + "，当前版本 " + inventory.version());
        }
    }
}
