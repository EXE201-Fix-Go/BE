package com.fixgo.dispatch;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** order_assignments — an invitation to one partner and what became of it. */
@Entity
@Table(name = "order_assignments")
public class OrderAssignment {
    public enum Status { OFFERED, ACCEPTED, DECLINED, EXPIRED, REASSIGNED, ENDED }
    public enum Source { BROADCAST, SHOP_REASSIGN, ADMIN_ASSIGN }

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "dispatch_round_id")
    private UUID dispatchRoundId;
    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;
    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_source", nullable = false, length = 15)
    private Source source;
    @Column(name = "assigned_by")
    private UUID assignedBy;
    @Column(name = "decline_reason")
    private String declineReason;
    @Column(name = "offered_at", nullable = false)
    private Instant offeredAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "arrived_at")
    private Instant arrivedAt;
    @Column(name = "ended_at")
    private Instant endedAt;

    protected OrderAssignment() { }

    public OrderAssignment(UUID orderId, UUID roundId, UUID partnerId, Source source, UUID assignedBy,
                           Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.dispatchRoundId = roundId;
        this.partnerId = partnerId;
        this.source = source;
        this.assignedBy = assignedBy;
        this.status = Status.OFFERED;
        this.offeredAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isOpenOffer(Instant now) {
        return status == Status.OFFERED && (expiresAt == null || expiresAt.isAfter(now));
    }
    public void accept(Instant now) { status = Status.ACCEPTED; acceptedAt = now; }
    public void decline(String reason, Instant now) { status = Status.DECLINED; declineReason = reason; endedAt = now; }
    public void expire(Instant now) { if (status == Status.OFFERED) { status = Status.EXPIRED; endedAt = now; } }
    public void end(Instant now) { if (endedAt == null) { status = Status.ENDED; endedAt = now; } }
    public void markArrived(Instant now) { arrivedAt = now; }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getDispatchRoundId() { return dispatchRoundId; }
    public UUID getPartnerId() { return partnerId; }
    public Status getStatus() { return status; }
    public Instant getOfferedAt() { return offeredAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getArrivedAt() { return arrivedAt; }
}
