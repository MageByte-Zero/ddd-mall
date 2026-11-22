package com.magebyte.ddd.mall.order.application;

import com.magebyte.ddd.mall.order.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.function.Predicate;

/** 只保存订单侧申请和已翻译结果；不发起渠道退款、不操作库存、不提供公网回调。 */
@Service
public class RefundApplicationService {
    private static final int MAX_ATTEMPTS = 3;
    private final OrderRepository orders;
    private final TransactionTemplate attemptTransaction;

    public RefundApplicationService(OrderRepository orders, PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.attemptTransaction = new TransactionTemplate(transactionManager);
        this.attemptTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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

    public PartialRefund requestPartialRefund(PartialRefundCommand command) {
        return updatePartialRefund(command.orderNo(), command.refundNo(), order ->
                order.requestPartialRefund(command.refundNo(), command.lineId(), command.quantity(),
                        command.reason(), LocalDateTime.now()));
    }

    public PartialRefund recordPartialResult(String orderNo, RefundResult result) {
        return updatePartialRefund(orderNo, result.refundNo(),
                order -> order.recordPartialRefundResult(result, LocalDateTime.now()));
    }

    private PartialRefund updatePartialRefund(String orderNo, String refundNo, Predicate<Order> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                return attemptTransaction.execute(transaction -> {
                    Order order = find(orderNo);
                    return (change.test(order) ? orders.save(order) : order).partialRefund(refundNo);
                });
            } catch (OrderVersionConflictException conflict) {
                if (attempt == MAX_ATTEMPTS) {
                    throw conflict;
                }
            }
        }
    }

    private Order find(String orderNo) {
        return orders.findByOrderNo(orderNo).orElseThrow(() -> new OrderNotFoundException(orderNo));
    }
}
