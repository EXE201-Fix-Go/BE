package com.fixgo.module.order.service;
import com.fixgo.module.order.entity.*;
import com.fixgo.module.order.enums.*;
import com.fixgo.module.order.dto.*;
import com.fixgo.module.order.repository.*;
import com.fixgo.module.order.service.*;


import com.fixgo.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.UUID;

/** Single choke point for status changes: validates against §7.1 and appends order_status_history. */
@Component
public class OrderStateMachine {
    private static final Logger log = LoggerFactory.getLogger(OrderStateMachine.class);
    private final OrderStatusHistoryRepository history;
    private final Clock clock;

    public OrderStateMachine(OrderStatusHistoryRepository history, Clock clock) {
        this.history = history;
        this.clock = clock;
    }

    public void transition(RescueOrder order, OrderStatus next, UUID changedBy, ActorType actorType, String note) {
        var from = order.getStatus();
        if (!from.canTransitionTo(next)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                    "Order in " + from + " cannot move to " + next + ".");
        }
        order.moveTo(next);
        history.save(new OrderStatusHistory(order.getId(), from, next, changedBy, actorType, note, clock.instant()));
        // No note/phone/address here: free text is customer-supplied. The order code is enough to find the history row.
        log.info("order.transition code={} {} -> {} actor={} actorId={}", order.getOrderCode(), from, next, actorType, changedBy);
    }

    /** For transitions performed by a conditional UPDATE (RB-36): the status is already in the DB, only log it. */
    public void recordExternal(UUID orderId, OrderStatus from, OrderStatus to, UUID changedBy, ActorType actorType,
                               String note) {
        if (!from.canTransitionTo(to)) throw new IllegalStateException(from + " -> " + to + " is not a legal transition.");
        history.save(new OrderStatusHistory(orderId, from, to, changedBy, actorType, note, clock.instant()));
        log.info("order.transition orderId={} {} -> {} actor={} actorId={}", orderId, from, to, actorType, changedBy);
    }

    public void recordCreation(RescueOrder order, UUID createdBy) {
        history.save(new OrderStatusHistory(order.getId(), null, order.getStatus(), createdBy, ActorType.CUSTOMER,
                null, clock.instant()));
        log.info("order.created code={} status={} customerId={}", order.getOrderCode(), order.getStatus(), createdBy);
    }

    public static ApiException wrongState(OrderStatus current, String action) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                "Cannot " + action + " an order in status " + current + ".");
    }
}
