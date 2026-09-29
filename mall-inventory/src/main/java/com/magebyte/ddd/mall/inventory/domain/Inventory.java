package com.magebyte.ddd.mall.inventory.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 库存（Inventory）聚合根：一个 SKU 一行库存，是库存限界上下文里唯一的一致性边界。
 *
 * <p><b>为什么第 12 讲要重写它。</b>第 10、11 讲的库存只有两个数字：总库存
 * {@code totalStock} 和可售 {@code availableStock}，扣减时只减可售、总库存纹丝不动。
 * 这留下一个无法回答的问题：库里 {@code total_stock=100, available_stock=93} 的时候，
 * 那"消失的 7 件"到底在哪？它既不在可售里，也没有任何字段记录它被谁占着。
 * 需求文档第 4 节第 2 条写的守恒——<b>已预占库存 + 可售库存 == 总库存</b>——
 * 从第 10 讲起一直是被破坏的，只是没人检查它。
 *
 * <p>本讲把它补成三个数字：
 *
 * <pre>
 *   总库存 totalStock  =  可售 availableStock  +  已预占 reservedStock
 *   仓库里一共还有多少件      现在能卖多少件          被订单占住、还没出库多少件
 * </pre>
 *
 * <p>加上"已预占"这一个数字，突然能回答三个之前答不上来的问题：这 7 件是被哪些订单
 * 占着的、多久没动了（超时该回收了）、以及取消订单时该还回去多少。
 *
 * <p><b>守护的不变量</b>（来源：需求文档
 * {@code projects/ddd-mall/fixtures/requirements/order-creation.md} 第 4 节）：
 * <ol>
 *   <li><b>守恒</b>：{@code availableStock + reservedStock == totalStock}——
 *       每一件货要么可卖、要么被占，不存在第三种状态；</li>
 *   <li>三个数字都不得为负；</li>
 *   <li>预占数量必须为正，且不得超过可售库存（这是"不超卖"的领域表达）；</li>
 *   <li>确认出库与释放预占的数量必须为正，且不得超过已预占数量——
 *       注意释放的上界是 <b>已预占</b> 而不是总库存，这是本讲对第 11 讲那条例外的修正。</li>
 * </ol>
 *
 * <p>第 11 讲的 {@code release} 用"可售不得还到总库存之上"当护栏，那是两字段模型下
 * 唯一能用的近似：当时没有"已预占"这个量，只能拿总库存当天花板。三字段之后这个近似
 * 必须换掉——如果同一笔订单的库存被释放两次，第二次撞的应该是"根本没有这么多已预占"，
 * 而不是"还完就超过总库存了"。后者在"订单 A 占了 5 件、订单 B 占了 5 件、总库存 100"
 * 的情况下完全拦不住重复释放。
 */
public class Inventory {

    private Long id;
    private final String skuCode;
    private final String skuName;
    private int totalStock;
    private int availableStock;
    private int reservedStock;
    /** 乐观并发版本号：由持久化层维护并自增，领域层只读（第 12 讲启用）。 */
    private int version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Inventory(String skuCode, String skuName, int totalStock,
                      int availableStock, int reservedStock) {
        this.skuCode = skuCode;
        this.skuName = skuName;
        this.totalStock = totalStock;
        this.availableStock = availableStock;
        this.reservedStock = reservedStock;
    }

    /**
     * 仓储重组入口：从持久化数据还原聚合，并当场校验守恒。
     *
     * <p>守恒校验放在重组而不是放在各条 SQL 里，是有意的：数据库里那些"说不通"的行
     * （比如手工改坏了、迁移漏了字段）要在被业务代码用到之前就炸出来，而不是等它
     * 悄悄参与计算、把错误放大成超卖。
     */
    public static Inventory reconstitute(Long id, String skuCode, String skuName,
                                         int totalStock, int availableStock, int reservedStock,
                                         int version,
                                         LocalDateTime createdAt, LocalDateTime updatedAt) {
        Objects.requireNonNull(id, "持久化库存的 id 不能为空");
        Objects.requireNonNull(skuCode, "SKU 编码不能为空");
        if (totalStock < 0 || availableStock < 0 || reservedStock < 0) {
            throw new InventoryDomainException("持久化数据违反库存非负：总库存 " + totalStock
                    + "，可售 " + availableStock + "，已预占 " + reservedStock);
        }
        if (availableStock + reservedStock != totalStock) {
            throw new InventoryDomainException("持久化数据违反库存守恒：总库存 " + totalStock
                    + "，可售 " + availableStock + "，已预占 " + reservedStock
                    + "（可售 + 已预占 应当等于总库存）");
        }
        Inventory inventory = new Inventory(skuCode, skuName, totalStock, availableStock, reservedStock);
        inventory.id = id;
        inventory.version = version;
        inventory.createdAt = createdAt;
        inventory.updatedAt = updatedAt;
        return inventory;
    }

    /**
     * 预占库存：下单时把货从"能卖"挪到"被占住"，总库存不变。
     *
     * <p>这是防止超卖的第一道闸门。它的语义是"这批货我已经许给某个订单了，别人不能再许"，
     * 而不是"这批货已经卖出去了"——货还躺在仓库里，所以总库存不动。
     * 真正让总库存减少的是 {@link #confirm(int)}。
     */
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new InventoryDomainException("预占数量必须为正数：" + quantity);
        }
        if (quantity > availableStock) {
            throw new InventoryDomainException("库存不足：SKU=" + skuCode
                    + "，可售 " + availableStock + "，请求预占 " + quantity);
        }
        this.availableStock -= quantity;
        this.reservedStock += quantity;
        this.updatedAt = LocalDateTime.now();
        assertConservation("预占");
    }

    /**
     * 确认出库：把已预占的货真正发走，已预占和总库存一起减少。
     *
     * <p>守恒式在出库后依然成立：{@code available + (reserved - n) == (total - n)}。
     * 也就是说<b>总库存只在货真的离开仓库时才变</b>——这正是预占模型区别于
     * "下单即扣总库存"写法的地方。
     */
    public void confirm(int quantity) {
        if (quantity <= 0) {
            throw new InventoryDomainException("确认出库数量必须为正数：" + quantity);
        }
        if (quantity > reservedStock) {
            throw new InventoryDomainException("确认出库数量超过已预占：SKU=" + skuCode
                    + "，已预占 " + reservedStock + "，请求出库 " + quantity);
        }
        this.reservedStock -= quantity;
        this.totalStock -= quantity;
        this.updatedAt = LocalDateTime.now();
        assertConservation("确认出库");
    }

    /**
     * 释放预占：订单取消、超时未支付时把货从"被占住"还回"能卖"，总库存不变。
     *
     * <p>与 {@link #reserve(int)} 严格互逆。上界是<b>已预占数量</b>而不是总库存：
     * 同一笔订单的库存被释放两次，第二次必然撞在这条守卫上。这是对第 11 讲那条
     * "可售不得还到总库存之上"护栏的替换——旧护栏在两字段模型下是唯一可用的近似，
     * 有了已预占这个量之后就不再是必要的，而且拦不住并发重复释放。
     */
    public void release(int quantity) {
        if (quantity <= 0) {
            throw new InventoryDomainException("释放数量必须为正数：" + quantity);
        }
        if (quantity > reservedStock) {
            throw new InventoryDomainException("释放数量超过已预占（疑似重复释放）：SKU=" + skuCode
                    + "，已预占 " + reservedStock + "，可售 " + availableStock
                    + "，请求释放 " + quantity);
        }
        this.reservedStock -= quantity;
        this.availableStock += quantity;
        this.updatedAt = LocalDateTime.now();
        assertConservation("释放预占");
    }

    /**
     * 补货入库：总库存与可售一起增加（采购到货、退货入库）。
     *
     * <p>它是守恒式里唯一让等号右边变大的入口。少了它，总库存只减不增，
     * 卖空一次就再也补不回来。
     */
    public void restock(int quantity) {
        if (quantity <= 0) {
            throw new InventoryDomainException("补货数量必须为正数：" + quantity);
        }
        this.totalStock += quantity;
        this.availableStock += quantity;
        this.updatedAt = LocalDateTime.now();
        assertConservation("补货入库");
    }

    /**
     * 守恒自检：每个动作之后都跑一遍。
     *
     * <p>这不是防御性编程的姿态。守恒是本聚合唯一的核心不变量，把它放在每个出口上
     * 自查，代价是一次整数加法，收益是任何改动只要破坏了守恒就会在单元测试里立刻响，
     * 而不是等到大促那天变成超卖。
     */
    private void assertConservation(String action) {
        if (availableStock + reservedStock != totalStock) {
            throw new InventoryDomainException(action + "后库存守恒被破坏：SKU=" + skuCode
                    + "，总库存 " + totalStock + "，可售 " + availableStock
                    + "，已预占 " + reservedStock);
        }
    }

    public Long id() {
        return id;
    }

    public String skuCode() {
        return skuCode;
    }

    public String skuName() {
        return skuName;
    }

    public int totalStock() {
        return totalStock;
    }

    public int availableStock() {
        return availableStock;
    }

    public int reservedStock() {
        return reservedStock;
    }

    /**
     * 拍一张当前三个数的快照。变动前调用一次、变动后调用一次，交给流水记录。
     */
    public StockSnapshot snapshot() {
        return new StockSnapshot(totalStock, availableStock, reservedStock);
    }

    /**
     * 乐观并发版本号。由持久化层在每次成功写入后自增，领域层只读。
     *
     * <p>版本号进领域对象而不是只留在持久化对象上，是因为"这份库存数据还是你读到的那一版吗"
     * 本身是个业务问题，不是存储细节：仓储要拿它去判定"有没有人在我读完之后动过这行"。
     */
    public int version() {
        return version;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }
}
