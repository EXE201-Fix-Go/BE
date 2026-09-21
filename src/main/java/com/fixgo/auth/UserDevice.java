package com.fixgo.auth;

import com.fixgo.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** user_devices — a refresh-token chain is bound to one device (RB-05). Biometrics stay local (QD-12). */
@Entity
@Table(name = "user_devices")
public class UserDevice {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "device_fingerprint", nullable = false, length = 200)
    private String deviceFingerprint;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DevicePlatform platform;
    @Column(name = "biometric_public_key")
    private String biometricPublicKey;
    @Column(name = "biometric_type", nullable = false, length = 20)
    private String biometricType = "NONE";
    @Column(name = "bound_at", nullable = false)
    private Instant boundAt;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "revoked_by")
    private UUID revokedBy;

    protected UserDevice() { }

    public UserDevice(User user, String deviceFingerprint, DevicePlatform platform, Instant now) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.deviceFingerprint = deviceFingerprint;
        this.platform = platform;
        this.boundAt = now;
        this.lastSeenAt = now;
    }

    public boolean isActive() { return revokedAt == null; }
    public void touch(Instant now) { lastSeenAt = now; }
    public void revoke(Instant now, UUID by) { if (revokedAt == null) { revokedAt = now; revokedBy = by; } }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public DevicePlatform getPlatform() { return platform; }
    public Instant getRevokedAt() { return revokedAt; }
}
