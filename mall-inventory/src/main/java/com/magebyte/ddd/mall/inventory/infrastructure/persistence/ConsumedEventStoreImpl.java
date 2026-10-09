package com.magebyte.ddd.mall.inventory.infrastructure.persistence;
import com.magebyte.ddd.mall.inventory.domain.ConsumedEventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
@Repository
public class ConsumedEventStoreImpl implements ConsumedEventStore {
    private final JdbcTemplate jdbc;
    public ConsumedEventStoreImpl(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean insertIfAbsent(String subscription, String eventId, String fingerprint) {
        // 只消化指定唯一键冲突；不用 INSERT IGNORE 吞掉截断等其他错误。
        try {
            return jdbc.update("INSERT INTO t_consumed_event(subscription,event_id,fingerprint) VALUES(?,?,?)",
                    subscription,eventId,fingerprint)==1;
        } catch (org.springframework.dao.DuplicateKeyException conflict) { return false; }
    }
    public Optional<String> fingerprint(String subscription,String eventId) {
        return jdbc.query("SELECT fingerprint FROM t_consumed_event WHERE subscription=? AND event_id=? LOCK IN SHARE MODE",
                (rs,n)->rs.getString(1),subscription,eventId).stream().findFirst();
    }
}
