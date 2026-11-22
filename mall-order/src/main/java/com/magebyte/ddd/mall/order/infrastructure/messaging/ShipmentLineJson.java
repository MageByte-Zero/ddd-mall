package com.magebyte.ddd.mall.order.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.magebyte.ddd.mall.order.domain.event.OrderShippedEvent;
import org.springframework.boot.jackson.JsonMixin;

/** 存量订单 v2 继续发出原来的三字段明细。 */
@JsonMixin(OrderShippedEvent.ShipmentLine.class)
abstract class ShipmentLineJson {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract String lineId();
}
