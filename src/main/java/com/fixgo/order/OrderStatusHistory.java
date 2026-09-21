package com.fixgo.order;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {
    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 25)
    private OrderStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 25)
    private OrderStatus toStatus;
    @Column(name = "changed_by")
    private UUID changedBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ActorType actorType;
    private String note;
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected OrderStatusHistory() { }

    public OrderStatusHistory(UUID orderId, OrderStatus from, OrderStatus to, UUID changedBy, ActorType actorType,
                              String note, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.fromStatus = from;
        this.toStatus = to;
        this.changedBy = changedBy;
        this.actorType = actorType;
        this.note = note;
        this.changedAt = now;
    }

    public OrderStatus getFromStatus() { return fromStatus; }
    public OrderStatus getToStatus() { return toStatus; }
    public ActorType getActorType() { return actorType; }
    public String getNote() { return note; }
    public Instant getChangedAt() { return changedAt; }
}
