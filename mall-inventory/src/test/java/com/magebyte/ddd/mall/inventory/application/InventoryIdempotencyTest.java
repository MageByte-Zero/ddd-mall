package com.magebyte.ddd.mall.inventory.application;

import com.magebyte.ddd.mall.inventory.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 MySQL；每个用例独立 SKU，不清理或重置已有教学数据。 */
@SpringBootTest
class InventoryIdempotencyTest {
    @Autowired IdempotentInventoryService service;
    @Autowired InventoryApplicationService legacy;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private String sku() {
        String sku="L13-"+UUID.randomUUID().toString().substring(0,12);
        jdbc.update("INSERT INTO t_inventory(sku_code,sku_name,total_stock,available_stock,reserved_stock) VALUES(?,?,10,10,0)",sku,"L13 fixture");
        return sku;
    }
    private InventoryOperation req(String key,String no,String sku,int qty) { return new InventoryOperation(key,no,sku,qty); }
    private int reserved(String sku) { return jdbc.queryForObject("SELECT reserved_stock FROM t_inventory WHERE sku_code=?",Integer.class,sku); }
    private int logs(String sku) { return jdbc.queryForObject("SELECT COUNT(*) FROM t_inventory_log WHERE sku_code=?",Integer.class,sku); }
    private String id() { return UUID.randomUUID().toString(); }

    @Test void legacy_duplicate_release_keeps_conservation_but_consumes_other_order() {
        String s=sku();
        legacy.reserve(s,2); legacy.reserve(s,3); legacy.release(s,2); legacy.release(s,2);
        assertEquals(1,reserved(s));
        assertEquals(10,jdbc.queryForObject("SELECT available_stock+reserved_stock FROM t_inventory WHERE sku_code=?",Integer.class,s));
        assertEquals(4,logs(s));
        System.out.println("L13_BASELINE total=10 available=9 reserved=1; B originally reserved=3; logs=4");
    }

    @Test void response_loss_replay_returns_original_snapshot_and_protects_B() {
        String s=sku(),a=id(),b=id();
        var first=req(id(),a,s,2); var original=service.reserve(first);
        service.reserve(req(id(),b,s,3));
        assertEquals(original,service.reserve(first),"原回执不能改成B操作后的当前库存");
        var release=req(id(),a,s,2);var result=service.release(release);
        assertEquals(result,service.release(release));
        assertEquals(3,reserved(s));assertEquals(3,logs(s));
        assertThrows(InventoryIdempotencyException.class,()->service.release(req(id(),a,s,2)));
        assertEquals(3,reserved(s));assertEquals(3,logs(s));
        System.out.println("L13_REPLAY A2+B3, release A twice => total=10 available=7 reserved=3 logs=3; changed-key release rejected");
        service.release(req(id(),b,s,3));
        assertEquals(0,reserved(s)); assertEquals(4,logs(s));
        System.out.println("L13_B_LEGAL_RELEASE total=10 available=10 reserved=0 logs=4; B independent action accepted");
    }

    @Test void same_key_changed_parameters_or_operation_rejected() {
        String s=sku(),other=sku(),key=id(),no=id();
        service.reserve(req(key,no,s,2));
        assertThrows(InventoryIdempotencyException.class,()->service.reserve(req(key,no,s,3)));
        assertThrows(InventoryIdempotencyException.class,()->service.reserve(req(key,no,other,2)));
        assertThrows(InventoryIdempotencyException.class,()->service.release(req(key,no,s,2)));
        assertEquals(2,reserved(s));assertEquals(0,reserved(other));
        // 一个预占单的不同动作必须使用不同 requestKey。
        service.release(req(id(),no,s,2));assertEquals(0,reserved(s));
        System.out.println("L13_CONFLICT changed quantity/SKU/action rejected; independent release key accepted");
    }

    @Test void concurrent_same_operation_only_once() throws Exception {
        String s=sku();var request=req(id(),id(),s,2);
        ExecutorService pool=Executors.newFixedThreadPool(16);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<InventoryOperationResult>> futures=new ArrayList<>();
            for(int i=0;i<32;i++) futures.add(pool.submit(()->{start.await();return service.reserve(request);}));
            start.countDown();var result=futures.getFirst().get(20,TimeUnit.SECONDS);
            for(var f:futures) assertEquals(result,f.get(20,TimeUnit.SECONDS));
            assertEquals(2,reserved(s));assertEquals(1,logs(s));
            System.out.println("L13_CONCURRENT requests=32 threads=16 identicalResults=32 reserved=2 logs=1");
        } finally {pool.shutdownNow();}
    }

    @Test void rollback_removes_receipt_business_change_and_log_then_same_key_retries() {
        String s=sku();var request=req(id(),id(),s,2);
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class,()->tx.execute(status->{service.reserve(request);throw new IllegalStateException("fixture failure after writes");}));
        assertEquals(0,reserved(s));assertEquals(0,logs(s));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_inventory_operation WHERE request_key=?",Integer.class,request.requestKey()));
        service.reserve(request);assertEquals(2,reserved(s));assertEquals(1,logs(s));
        System.out.println("L13_ROLLBACK failureAfterWrites => reserved=0 receipt=0 logs=0; same-key retry => reserved=2 logs=1");
    }

    @Test void release_and_confirm_are_mutually_exclusive_and_late_reserve_replay_is_safe() {
        String s=sku(),no=id();var reserve=req(id(),no,s,2);var first=service.reserve(reserve);
        service.confirm(req(id(),no,s,2));
        assertThrows(InventoryDomainException.class,()->service.release(req(id(),no,s,2)));
        assertEquals(first,service.reserve(reserve));assertEquals(0,reserved(s));assertEquals(2,logs(s));
        assertEquals(8,jdbc.queryForObject("SELECT total_stock FROM t_inventory WHERE sku_code=?",Integer.class,s));
        System.out.println("L13_LIFECYCLE confirmed reservation cannot release; late reserve replay unchanged; total=8 reserved=0 logs=2");
    }
}
