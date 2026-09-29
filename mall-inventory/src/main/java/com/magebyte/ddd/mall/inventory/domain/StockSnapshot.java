package com.magebyte.ddd.mall.inventory.domain;

/**
 * 库存快照（值对象）：某个时刻"总库存 / 可售 / 已预占"三个数的一组取值。
 *
 * <p>它的唯一用途是在一次库存变动的前后各拍一张，交给流水记录。
 * 用不可变 record 而不是直接传 {@link Inventory}，是因为聚合是可变的——
 * 调用 {@code reserve()} 之后那个对象已经变成"变动后"的状态了，
 * 拿它当"变动前"的记录只会记下一对一模一样的数字。
 *
 * <p>顺带一个判别标准：快照没有身份、不关心是哪个 SKU 的（SKU 由调用方另行携带），
 * 三个数相等就是同一个快照——这是值对象的典型长相。
 */
public record StockSnapshot(int totalStock, int availableStock, int reservedStock) {

    /** 校验这张快照自身是否满足守恒，便于流水落库前兜底。 */
    public boolean conservative() {
        return availableStock + reservedStock == totalStock;
    }
}
