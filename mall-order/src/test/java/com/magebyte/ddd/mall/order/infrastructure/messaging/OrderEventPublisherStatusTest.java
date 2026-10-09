package com.magebyte.ddd.mall.order.infrastructure.messaging;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.*;
import com.magebyte.ddd.mall.order.domain.event.OrderCreatedEvent;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class OrderEventPublisherStatusTest {
    @Test void every_non_ok_status_throws_instead_of_claiming_success() {
        for (SendStatus status:SendStatus.values()) {
            RocketMQTemplate template=mock(RocketMQTemplate.class);
            SendResult result=new SendResult(); result.setSendStatus(status);
            when(template.syncSend(anyString(),any(org.springframework.messaging.Message.class))).thenReturn(result);
            var publisher=new OrderEventPublisher(template);
            var event=OrderCreatedEvent.raise("STATUS",1L,LocalDateTime.now());
            if (status==SendStatus.SEND_OK) assertDoesNotThrow(()->publisher.publishAll(List.of(event)));
            else assertThrows(IllegalStateException.class,()->publisher.publishAll(List.of(event)));
        }
    }
}
