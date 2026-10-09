package com.magebyte.ddd.mall.order.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.order.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class ShipmentOutboxTest {
    private final List<String> created = new ArrayList<>();
    @AfterEach void quarantineOwnFacts() {
        // 保留合成 fixture 数据，但不交给未来生产中继或干扰其他测试的待发送计数。
        for (String orderNo : created) jdbc.update(
                "UPDATE t_outbox_event SET status='FAILED' WHERE aggregate_id=? AND status='PENDING'", orderNo);
    }
    @Autowired OrderRepository orders;
    @Autowired OrderApplicationService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void shipping_use_case_commits_state_and_v2_fact_snapshot() throws Exception {
        Order order=Order.create(9414L,new Address("张三","13800000000","测试地址"));
        created.add(order.orderNo());
        order.addItem(OrderItem.create(14L,9414L,"sku fixture",2,Money.of("10.00")));
        order.markPaid(Money.of("20.00"),"payment",LocalDateTime.now());
        new TransactionTemplate(manager).execute(status->{orders.save(order);return null;});
        var result=service.shipOrder(new ShipOrderCommand(order.orderNo(),"merchant:14"));
        assertEquals("SHIPPED",result.status());
        String body=jdbc.queryForObject("SELECT payload FROM t_outbox_event WHERE aggregate_id=? AND event_type='OrderShipped'",String.class,order.orderNo());
        var event=json.readTree(body);assertEquals(2,event.path("schemaVersion").asInt());assertEquals(2,event.path("lines").get(0).path("quantity").asInt());assertEquals(order.orderNo()+":SKU-9414",event.path("lines").get(0).path("reservationNo").asText());
        assertThrows(OrderDomainException.class,()->service.shipOrder(new ShipOrderCommand(order.orderNo(),"merchant:14")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id=? AND event_type='OrderShipped'",Integer.class,order.orderNo()));
        System.out.println("L14_SHIP_USE_CASE order="+order.orderNo()+" state=SHIPPED schemaVersion=2 lines=1; repeat ship rejected eventCount=1");
    }
}
