package com.magebyte.ddd.mall.inventory.fixture;
import com.magebyte.ddd.mall.inventory.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.inventory.domain.*;
import com.magebyte.ddd.mall.inventory.interfaces.messaging.InventoryShipmentConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class ShipmentReliabilityTest {
    @Autowired ShipmentApplicationService shipments;
    @Autowired IdempotentInventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private String id(){return UUID.randomUUID().toString();}
    private String sku(){String s="L14-T-"+id().substring(0,12);jdbc.update("INSERT INTO t_inventory(sku_code,sku_name,total_stock,available_stock,reserved_stock) VALUES(?,?,10,10,0)",s,"L14 test");return s;}
    private ShipmentFact fact(String event,String order,String sku,int qty,String fingerprint){return new ShipmentFact(event,order,List.of(new ShipmentFact.Line(order+":"+sku,sku,qty)),fingerprint);}
    private int confirms(String s){return jdbc.queryForObject("SELECT COUNT(*) FROM t_inventory_log WHERE sku_code=? AND change_type='CONFIRM'",Integer.class,s);}
    private int inbox(String e){return jdbc.queryForObject("SELECT COUNT(*) FROM t_consumed_event WHERE event_id=?",Integer.class,e);}
    private void reserve(String o,String s,int q){inventory.reserve(new InventoryOperation("RESERVE:"+o+":"+s,o+":"+s,s,q));}
    @Test void concurrent_same_event_and_legal_other_order() throws Exception {
        String s=sku(),a=id(),b=id(),e=id();reserve(a,s,2);reserve(b,s,3);
        var fact=fact(e,a,s,2,"1".repeat(64));
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try {List<Future<Boolean>> futures=new ArrayList<>();for(int i=0;i<16;i++)futures.add(pool.submit(()->{start.await();return shipments.process(fact);}));start.countDown();int applied=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))applied++;
            assertEquals(1,applied);assertEquals(1,inbox(e));assertEquals(1,confirms(s));
            shipments.process(fact(id(),a,s,2,"1".repeat(64)));assertEquals(1,confirms(s));
            shipments.process(fact(id(),b,s,3,"2".repeat(64)));assertEquals(2,confirms(s));assertEquals(0,jdbc.queryForObject("SELECT reserved_stock FROM t_inventory WHERE sku_code=?",Integer.class,s));
            System.out.println("L14_CONCURRENT events=16 applied=1; new eventId same action no extra confirm; B legal total=5 reserved=0 confirms=2");
        }finally{pool.shutdownNow();}
    }
    @Test void conflicts_never_commit_an_inbox_or_change_inventory() {
        String s=sku(),other=sku(),a=id(),e=id();reserve(a,s,2);shipments.process(fact(e,a,s,2,"1".repeat(64)));
        for(var wrong:List.of(fact(e,a,s,3,"2".repeat(64)),fact(e,a,other,2,"3".repeat(64)),fact(e,id(),s,2,"4".repeat(64))))assertThrows(InventoryIdempotencyException.class,()->shipments.process(wrong));
        String newId=id();assertThrows(InventoryIdempotencyException.class,()->shipments.process(fact(newId,a,s,3,"5".repeat(64))));assertEquals(0,inbox(newId));assertEquals(1,confirms(s));assertEquals(0,confirms(other));
        System.out.println("L14_CONFLICT same eventId quantity/SKU/order and new eventId changed business content rejected");
    }
    @Test void second_sku_failure_rolls_back_every_previous_write() {
        List<String> skus=new ArrayList<>(List.of(sku(),sku()));Collections.sort(skus);String a=id(),e=id();reserve(a,skus.getFirst(),2);
        var fact=new ShipmentFact(e,a,skus.stream().map(s->new ShipmentFact.Line(a+":"+s,s,2)).toList(),"1".repeat(64));
        assertThrows(InventoryDomainException.class,()->shipments.process(fact));assertEquals(0,inbox(e));assertEquals(0,confirms(skus.getFirst()));assertEquals("RESERVED",jdbc.queryForObject("SELECT state FROM t_inventory_reservation WHERE reservation_no=?",String.class,a+":"+skus.getFirst()));
        reserve(a,skus.getLast(),2);assertTrue(shipments.process(fact));assertEquals(1,inbox(e));for(String s:skus)assertEquals(1,confirms(s));
        System.out.println("L14_MULTI_SKU second reservation missing => inbox=0 confirms=0 first RESERVED; repair then all commit");
    }
    @Test void wire_contract_rejects_wrong_types_version_and_action() throws Exception {
        String s=sku(),a=id();var body=json.writeValueAsString(Map.of("eventId",id(),"eventName","OrderShipped","schemaVersion",2,"orderNo",a,"operatedBy","merchant:1","occurredOn","2026-10-08T10:00:00","lines",List.of(Map.of("reservationNo",a+":"+s,"skuCode",s,"quantity",2))));
        var consumer=new InventoryShipmentConsumer(json,shipments);
        for(String wrong:List.of(body.replace("\"quantity\":2","\"quantity\":2.7"),body.replace("\"quantity\":2","\"quantity\":\"2\""),body.replace("\"schemaVersion\":2","\"schemaVersion\":1"),body.replace("OrderShipped","OrderCancelled")))assertThrows(IllegalArgumentException.class,()->consumer.onMessage(wrong));
        assertEquals(0,confirms(s));
    }
}
