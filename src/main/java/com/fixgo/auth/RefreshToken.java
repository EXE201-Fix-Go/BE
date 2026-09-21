package com.fixgo.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** refresh_tokens — rotated on every use; the old row points at its replacement (RB-05). */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private UserDevice device;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "replaced_by")
    private UUID replacedBy;
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "revoke_reason", length = 50)
    private String revokeReason;

    protected RefreshToken() { }

    public RefreshToken(UserDevice device, String tokenHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.device = device;
        this.tokenHash = tokenHash;
        this.issuedAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() { return usedAt != null; }
    public boolean isLive(Instant now) { return usedAt == null && revokedAt == null && expiresAt.isAfter(now); }
    public void rotateTo(RefreshToken next, Instant now) { usedAt = now; replacedBy = next.getId(); }
    public void revoke(Instant now, String reason) { if (revokedAt == null) { revokedAt = now; revokeReason = reason; } }

    public UUID getId() { return id; }
    public UserDevice getDevice() { return device; }
    public Instant getExpiresAt() { return expiresAt; }
}
