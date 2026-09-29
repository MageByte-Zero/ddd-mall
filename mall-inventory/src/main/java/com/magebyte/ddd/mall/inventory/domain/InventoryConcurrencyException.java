package com.magebyte.ddd.mall.inventory.domain;

/**
 * 库存并发冲突：准备写入的这一行，在你读完之后已经被别人改过了。
 *
 * <p>它和 {@link InventoryDomainException} 是两回事，必须分开：
 * <ul>
 *   <li>{@link InventoryDomainException} 是<b>业务不允许</b>——库存不足、数量非法、
 *       释放量超过已预占。重试一万次结果都一样，接口层翻译成 422。</li>
 *   <li>本异常是<b>时机不巧</b>——读到的数据已经过期，重新读一次可能就成了。
 *       接口层翻译成 409 Conflict，由调用方决定重试还是放弃。</li>
 * </ul>
 *
 * <p>把它们合成一个异常是最省事的写法，代价是调用方没法区分"该重试"和"该报错"，
 * 最后要么无脑重试把业务错误重试一万次，要么一律放弃把并发冲突当成库存不足抛给用户。
 *
 * <p>第 12 讲的乐观锁路径（释放预占 / 确认出库 / 补货）在版本冲突时抛这个；
 * 预占路径走原子条件更新，不会走到这里——原因见 {@link InventoryRepository}
 * 上关于两条写路径的取舍说明。
 */
public class InventoryConcurrencyException extends RuntimeException {

    public InventoryConcurrencyException(String message) {
        super(message);
    }
}
