package com.magebyte.ddd.mall.inventory.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magebyte.ddd.mall.inventory.InventoryApplication;
import com.magebyte.ddd.mall.inventory.application.*;
import com.magebyte.ddd.mall.inventory.domain.InventoryOperation;
import com.magebyte.ddd.mall.inventory.interfaces.messaging.InventoryShipmentConsumer;
import org.apache.rocketmq.client.consumer.*;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 子进程故障夹具。真实客户端与 Broker；fault 只注入明确的应用/回调边界。 */
public class ReliabilityProcess {
    public static void main(String[] args) throws Exception {
        String mode=args[0], run=args[1], group=args.length>2?args[2]:"L14-"+run;
        String topic="L14-"+run;
        if (mode.equals("send")) {
            DefaultMQProducer producer=new DefaultMQProducer("L14-P-"+UUID.randomUUID());
            producer.setNamesrvAddr("localhost:9876"); producer.start();
            try {
                byte[] body=Files.readAllBytes(Path.of(args[3]));
                String event=new ObjectMapper().readTree(body).path("eventId").asText();
                System.out.println("SEND_RESULT "+producer.send(new Message(topic,"OrderShipped",event,body)));
            } finally {producer.shutdown();}
            return;
        }
        if (mode.startsWith("dlq")) {
            DefaultMQPullConsumer pull=new DefaultMQPullConsumer("L14-inspect-"+UUID.randomUUID());
            pull.setNamesrvAddr("localhost:9876");pull.start();
            try {
                for (MessageQueue queue:pull.fetchSubscribeMessageQueues("%DLQ%"+group)) {
                    PullResult result=pull.pull(queue,"*",0,32);
                    if (result.getMsgFoundList()!=null) for (MessageExt message:result.getMsgFoundList()) {
                        Files.write(Path.of(args[3]),message.getBody());
                        System.out.println("DLQ_FOUND id="+message.getMsgId()+" reconsumeTimes="+message.getReconsumeTimes()+" body="+new String(message.getBody(),StandardCharsets.UTF_8));
                    }
                }
            } finally {pull.shutdown();}
            return;
        }
        var app=new SpringApplicationBuilder(InventoryApplication.class).web(WebApplicationType.NONE).run(
                "--spring.cloud.nacos.discovery.enabled=false", "--ddd.inventory.shipment-consumer-enabled="+mode.equals("starter"),
                "--ddd.inventory.shipment-topic="+topic,"--ddd.inventory.shipment-group="+group,
                "--seata.enabled=false","--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl");
        JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
        ObjectMapper json=app.getBean(ObjectMapper.class);
        IdempotentInventoryService inventory=app.getBean(IdempotentInventoryService.class);
        InventoryShipmentConsumer adapter=new InventoryShipmentConsumer(json,app.getBean(ShipmentApplicationService.class));
        String sku="L14-"+run, a="A-"+run,b="B-"+run;
        if (mode.equals("init")) {
            jdbc.update("INSERT INTO t_inventory(sku_code,sku_name,total_stock,available_stock,reserved_stock) VALUES(?,?,10,10,0)",sku,"L14 fixture");
            inventory.reserve(new InventoryOperation("RESERVE:"+a,a+":"+sku,sku,2));
            inventory.reserve(new InventoryOperation("RESERVE:"+b,b+":"+sku,sku,3));
            for (String order:List.of(a,b,"C-"+run)) {
                String body=json.writeValueAsString(Map.of("eventId","E-"+order,"eventName","OrderShipped","schemaVersion",2,
                        "orderNo",order,"operatedBy","merchant:1","occurredOn","2026-10-08T10:00:00",
                        "lines",List.of(Map.of("reservationNo",order+":"+sku,"skuCode",sku,"quantity",order.equals(b)?3:2))));
                Files.writeString(Path.of(args[3],order+".json"),body);
            }
            dump(jdbc,sku,run);app.close();return;
        }
        if (mode.equals("repair")) {
            inventory.reserve(new InventoryOperation("RESERVE:C-"+run,"C-"+run+":"+sku,sku,2));
            dump(jdbc,sku,run);app.close();return;
        }
        if (mode.equals("dump")) {dump(jdbc,sku,run);app.close();return;}
        if (mode.equals("checks")) {
            String body=Files.readString(Path.of(args[3]));
            var fact=adapter.translate(body);
            adapter.onMessage(body);
            adapter.onMessage(body.replace(fact.eventId(),"NEW-"+fact.eventId()));
            for (String wrong:List.of(body.replace("\"quantity\":2","\"quantity\":3"),body.replace("\"quantity\":2","\"quantity\":2.7"),body.replace("\"quantity\":2","\"quantity\":\"2\""))) {
                try {adapter.onMessage(wrong);throw new AssertionError("conflict unexpectedly accepted");}
                catch (IllegalArgumentException|com.magebyte.ddd.mall.inventory.domain.InventoryIdempotencyException expected) {System.out.println("CONFLICT_REJECTED "+expected);}
            }
            dump(jdbc,sku,run);app.close();return;
        }
        if (mode.equals("starter")) {System.out.println("STARTER_READY "+group);System.out.flush();Thread.sleep(600000);app.close();return;}
        DefaultMQPushConsumer consumer=new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr("localhost:9876");consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(1);consumer.setConsumeThreadMax(1);consumer.setMaxReconsumeTimes(2);
        consumer.subscribe(topic,"OrderShipped");AtomicBoolean once=new AtomicBoolean();
        TransactionTemplate tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        consumer.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
            for (MessageExt message:messages) {
                String body=new String(message.getBody(),StandardCharsets.UTF_8);
                System.out.println("DELIVERY id="+jsonId(json,body)+" reconsume="+message.getReconsumeTimes()+" msgId="+message.getMsgId());System.out.flush();
                try {
                    if (mode.equals("before") && once.compareAndSet(false,true)) {
                        tx.execute(status->{adapter.onMessage(body);throw new IllegalStateException("before local commit");});
                    } else {
                        adapter.onMessage(body);
                        if (mode.equals("halt") && once.compareAndSet(false,true)) {
                            System.out.println("HALT_AFTER_COMMIT_BEFORE_CALLBACK_SUCCESS");System.out.flush();Runtime.getRuntime().halt(136);
                        }
                        if (mode.equals("after") && once.compareAndSet(false,true)) throw new IllegalStateException("after local commit before callback success");
                    }
                    System.out.println("CALLBACK_SUCCESS id="+jsonId(json,body));
                } catch (Exception failure) {
                    System.out.println("CALLBACK_FAILURE "+failure);dump(jdbc,sku,run);System.out.flush();
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            dump(jdbc,sku,run);System.out.flush();return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();System.out.println("CONSUMER_READY "+group);System.out.flush();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{consumer.shutdown();app.close();}));
        Thread.sleep(600000);
    }
    private static String jsonId(ObjectMapper json,String body) {
        try{return json.readTree(body).path("eventId").asText();}catch(Exception e){return "invalid";}
    }
    private static void dump(JdbcTemplate jdbc,String sku,String run) {
        System.out.println("DB_STATE "+jdbc.queryForList("SELECT total_stock,available_stock,reserved_stock FROM t_inventory WHERE sku_code=?",sku)
                +" confirmLogs="+jdbc.queryForObject("SELECT COUNT(*) FROM t_inventory_log WHERE sku_code=? AND change_type='CONFIRM'",Integer.class,sku)
                +" inbox="+jdbc.queryForObject("SELECT COUNT(*) FROM t_consumed_event WHERE event_id LIKE ?",Integer.class,"%"+run+"%"));
    }
}
