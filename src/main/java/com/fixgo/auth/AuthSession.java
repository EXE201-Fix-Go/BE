package com.fixgo.auth;

import com.fixgo.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
public class AuthSession {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant revokedAt;

    protected AuthSession() { }

    public AuthSession(User user, Instant now, Instant expiresAt) {
        id = UUID.randomUUID();
        this.user = user;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isActive(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
    public void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
    public UUID getId() { return id; }
    public User getUser() { return user; }
    public Instant getExpiresAt() { return expiresAt; }
}
