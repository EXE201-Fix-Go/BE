package com.fixgo.payment;

import com.fixgo.common.Actor;
import com.fixgo.common.ApiException;
import com.fixgo.dispatch.DispatchService;
import com.fixgo.order.OrderStatus;
import com.fixgo.order.RescueOrderRepository;
import com.fixgo.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/review")
public class ReviewController {
    private final ReviewRepository reviews;
    private final RescueOrderRepository orders;
    private final DispatchService dispatch;
    private final Clock clock;

    public ReviewController(ReviewRepository reviews, RescueOrderRepository orders, DispatchService dispatch, Clock clock) {
        this.reviews = reviews;
        this.orders = orders;
        this.dispatch = dispatch;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ReviewResponse create(Authentication auth, @PathVariable UUID orderId, @Valid @RequestBody ReviewRequest request) {
        var actor = Actor.of(auth);
        var order = orders.lockById(orderId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order does not exist."));
        if (!actor.is(Role.CUSTOMER) || !order.getCustomerId().equals(actor.userId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order does not exist.");
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_COMPLETED", "Only completed orders can be reviewed.");
        }
        if (reviews.existsByOrderId(orderId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "This order already has a review.");
        }
        var partnerId = dispatch.currentAssignment(orderId).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "NO_PARTNER", "No partner served this order.")).getPartnerId();
        var review = reviews.save(new Review(orderId, partnerId, request.rating(), request.feedback(), clock.instant()));
        return new ReviewResponse(review.getId(), orderId, review.getRating(), review.getFeedback(), review.getCreatedAt());
    }

    public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 1000) String feedback) { }
    public record ReviewResponse(UUID id, UUID orderId, int rating, String feedback, Instant createdAt) { }
}
