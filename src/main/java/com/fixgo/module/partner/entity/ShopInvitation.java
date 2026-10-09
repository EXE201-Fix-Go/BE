package com.fixgo.module.partner.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** shop_invitations: a shop owner's invitation for a phone number. Accepting is the invitee's own decision. */
@Entity
@Table(name = "shop_invitations")
public class ShopInvitation {
    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }

    @Id
    private UUID id;
    @Column(name = "shop_id", nullable = false)
    private UUID shopId;
    @Column(name = "invitee_phone", nullable = false, length = 20)
    private String inviteePhone;
    @Column(name = "invitee_name", nullable = false, length = 100)
    private String inviteeName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "responded_at")
    private Instant respondedAt;
    @Column(name = "invitee_user_id")
    private UUID inviteeUserId;

    protected ShopInvitation() { }

    public ShopInvitation(UUID shopId, String inviteePhone, String inviteeName, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.shopId = shopId;
        this.inviteePhone = inviteePhone;
        this.inviteeName = inviteeName;
        this.status = Status.PENDING;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isOpen(Instant now) { return status == Status.PENDING && expiresAt.isAfter(now); }
    public void accept(UUID userId, Instant now) { status = Status.ACCEPTED; inviteeUserId = userId; respondedAt = now; }
    public void decline(UUID userId, Instant now) { status = Status.DECLINED; inviteeUserId = userId; respondedAt = now; }
    public void cancel(Instant now) { status = Status.CANCELLED; respondedAt = now; }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public String getInviteePhone() { return inviteePhone; }
    public String getInviteeName() { return inviteeName; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
