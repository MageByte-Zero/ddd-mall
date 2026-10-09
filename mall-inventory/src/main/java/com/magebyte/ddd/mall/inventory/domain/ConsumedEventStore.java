package com.magebyte.ddd.mall.inventory.domain;
import java.util.Optional;
public interface ConsumedEventStore {
    /** 唯一约束竞争，不用先查再插。返回 false 表示已提交记录存在。 */
    boolean insertIfAbsent(String subscription, String eventId, String fingerprint);
    Optional<String> fingerprint(String subscription, String eventId);
}
