package com.fixgo.module.payment.service;

import com.fixgo.module.dispatch.service.DispatchService;
import com.fixgo.module.iam.enums.Role;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.order.repository.RescueOrderRepository;
import com.fixgo.module.payment.entity.Review;
import com.fixgo.module.payment.repository.ReviewRepository;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.util.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** RB-61: one review per order, written by the order's customer for the partner who served it. */
@Service
public class ReviewService {
    private final ReviewRepository reviews;
    private final RescueOrderRepository orders;
    private final DispatchService dispatch;
    private final Clock clock;

    public ReviewService(ReviewRepository reviews, RescueOrderRepository orders, DispatchService dispatch, Clock clock) {
        this.reviews = reviews;
        this.orders = orders;
        this.dispatch = dispatch;
        this.clock = clock;
    }

    /** The caller check, the state check and the insert run in one transaction under the order lock (NT-02). */
    @Transactional
    public Review create(Actor actor, UUID orderId, int rating, String feedback) {
        var order = orders.lockById(orderId).orElseThrow(ReviewService::orderNotFound);
        if (!actor.is(Role.CUSTOMER) || !order.getCustomerId().equals(actor.userId())) throw orderNotFound();
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_COMPLETED", "Only completed orders can be reviewed.");
        }
        if (reviews.existsByOrderId(orderId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "This order already has a review.");
        }
        var partnerId = dispatch.currentAssignment(orderId).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "NO_PARTNER", "No partner served this order.")).getPartnerId();
        return reviews.save(new Review(orderId, partnerId, rating, feedback, clock.instant()));
    }

    private static ApiException orderNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order does not exist.");
    }
}
