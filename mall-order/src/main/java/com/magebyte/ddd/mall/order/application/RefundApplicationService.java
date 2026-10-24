package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** 只保存订单侧申请和已翻译结果；不发起渠道退款、不操作库存、不提供公网回调。 */
@Service
public class RefundApplicationService {
    private final OrderRepository orders;
    public RefundApplicationService(OrderRepository orders) { this.orders = orders; }

    @Transactional
    public Refund requestFullRefund(FullRefundCommand command) {
        Order order = find(command.orderNo());
        order.requestFullRefund(command.refundNo(), command.amount(), command.reason(),
                command.operatedBy(), LocalDateTime.now());
        return orders.save(order).refund();
    }

    @Transactional
    public Refund recordResult(String orderNo, RefundResult result, String operatedBy) {
        Order order = find(orderNo);
        order.recordRefundResult(result, operatedBy, LocalDateTime.now());
        return orders.save(order).refund();
    }
    private Order find(String orderNo) {
        return orders.findByOrderNo(orderNo).orElseThrow(() -> new OrderNotFoundException(orderNo));
    }
}
