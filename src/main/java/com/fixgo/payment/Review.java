package com.fixgo.payment;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reviews")
public class Review {
    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;
    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;
    @Column(nullable = false)
    private short rating;
    private String feedback;
    @Column(name = "is_hidden", nullable = false)
    private boolean hidden;
    @Column(name = "hidden_by")
    private UUID hiddenBy;
    @Column(name = "hidden_reason")
    private String hiddenReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Review() { }

    public Review(UUID orderId, UUID partnerId, int rating, String feedback, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.partnerId = partnerId;
        this.rating = (short) rating;
        this.feedback = feedback;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public int getRating() { return rating; }
    public String getFeedback() { return feedback; }
    public Instant getCreatedAt() { return createdAt; }
}
