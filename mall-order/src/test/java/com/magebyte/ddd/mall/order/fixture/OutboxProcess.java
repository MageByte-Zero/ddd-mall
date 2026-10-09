package com.magebyte.ddd.mall.order.fixture;
import com.magebyte.ddd.mall.order.OrderApplication;
import com.magebyte.ddd.mall.order.domain.event.*;
import com.magebyte.ddd.mall.order.infrastructure.messaging.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.apache.seata.core.context.RootContext;
import java.nio.file.*;
import java.util.*;

/** 实际独立进程、实际 TC 全局事务、实际 Broker；只改专用夹具行。 */
public class OutboxProcess {
    static String mode;
    @TestConfiguration
    public static class CrashPublisherConfig {
        @Bean @Primary
        public DomainEventPublisher crashPublisher(OrderEventPublisher actual, org.apache.rocketmq.spring.core.RocketMQTemplate template) {
            return events->{
                if (mode.equals("unavailable")) {
                    try {
                        var producer=template.getProducer();
                        String topic="L14-"+System.getProperty("l14.run");
                        producer.send(new org.apache.rocketmq.common.message.Message(topic,"Probe","{}".getBytes()));
                        var client=producer.getDefaultMQProducerImpl().getmQClientFactory();
                        var field=client.getClass().getDeclaredField("brokerAddrTable");field.setAccessible(true);
                        var addresses=(java.util.Map<String,java.util.HashMap<Long,String>>)field.get(client);
                        addresses.values().forEach(map->map.replaceAll((id,address)->"127.0.0.1:1"));
                        producer.setVipChannelEnabled(false);producer.setRetryTimesWhenSendFailed(0);
                        System.out.println("FAULT_ROUTE brokerAddress=127.0.0.1:1 (test JVM only)");
                    } catch (Exception failure) {throw new IllegalStateException(failure);}
                }
                actual.publishAll(events);
                if (mode.equals("halt")) {System.out.println("HALT_AFTER_SEND_BEFORE_SENT");System.out.flush();Runtime.getRuntime().halt(137);}
            };
        }
    }
    public static void main(String[] args) throws Exception {
        mode=args[0];String run=args[1];System.setProperty("l14.run",run);
        var app=new SpringApplicationBuilder(OrderApplication.class,CrashPublisherConfig.class).web(WebApplicationType.NONE).run(
                "--spring.cloud.nacos.discovery.enabled=false","--ddd.outbox.relay-initial-delay-ms=3600000",
                "--ddd.order.events-topic=L14-"+run,"--seata.enabled="+(!mode.equals("init") && !mode.equals("bootstrap")),
                "--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl");
        JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
        if (mode.equals("bootstrap")) {System.out.println("ORDER_SCHEMA_READY");app.close();return;}
        if (mode.equals("init")) {
            String payload=Files.readString(Path.of(args[2]));
            jdbc.update("INSERT INTO t_outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,retry_count) VALUES(?,?,?,?,?,'PENDING',0)",
                    "E-A-"+run,"Order","A-"+run,"OrderShipped",payload);
            System.out.println("OUTBOX_INIT E-A-"+run);app.close();return;
        }
        if (mode.startsWith("at-")) {
            var global=GlobalTransactionContext.getCurrentOrCreate();global.begin(60000,"L14-relay-gate");
            System.out.println("GLOBAL_BEGIN "+RootContext.getXID());
            TransactionTemplate tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
            tx.execute(status->{
                jdbc.update("INSERT INTO t_outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,retry_count) VALUES(?,?,?,?,?,'PENDING',0)",
                        "E-A-"+run,"Order","A-"+run,"OrderShipped",FilesRead.read(args[2]));return null;
            });
            System.out.println("BRANCH_COMMITTED E-A-"+run);System.out.flush();Thread.sleep(12000);
            if (mode.equals("at-rollback")) global.rollback(); else global.commit();
            System.out.println("GLOBAL_FINISHED "+mode);System.out.flush();app.close();return;
        }
        Long id=jdbc.queryForObject("SELECT id FROM t_outbox_event WHERE event_id=?",Long.class,"E-A-"+run);
        try {System.out.println("DELIVER_RESULT "+app.getBean(OutboxEventDelivery.class).deliver(id));}
        catch(Exception failure){System.out.println("DELIVER_BLOCKED "+failure);}
        System.out.println("OUTBOX_STATE "+jdbc.queryForList("SELECT event_id,status,retry_count FROM t_outbox_event WHERE event_id=?","E-A-"+run));
        app.close();
    }
    static class FilesRead {static String read(String file){try{return Files.readString(Path.of(file));}catch(Exception e){throw new RuntimeException(e);}}}
}
