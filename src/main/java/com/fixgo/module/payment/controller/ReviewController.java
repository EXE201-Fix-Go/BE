package com.fixgo.module.payment.controller;

import com.fixgo.module.payment.service.ReviewService;
import com.fixgo.shared.util.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/review")
public class ReviewController {
    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponse create(Authentication auth, @PathVariable UUID orderId, @Valid @RequestBody ReviewRequest request) {
        var review = reviews.create(Actor.of(auth), orderId, request.rating(), request.feedback());
        return new ReviewResponse(review.getId(), orderId, review.getRating(), review.getFeedback(), review.getCreatedAt());
    }

    public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 1000) String feedback) { }
    public record ReviewResponse(UUID id, UUID orderId, int rating, String feedback, Instant createdAt) { }
}
