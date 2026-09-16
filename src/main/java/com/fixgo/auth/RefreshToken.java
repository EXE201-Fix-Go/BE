package com.fixgo.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AuthSession session;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant usedAt;

    protected RefreshToken() { }

    public RefreshToken(AuthSession session, String hash, Instant now) {
        id = UUID.randomUUID();
        this.session = session;
        tokenHash = hash;
        createdAt = now;
    }

    public AuthSession getSession() { return session; }
    public boolean isUsed() { return usedAt != null; }
    public void markUsed(Instant now) { usedAt = now; }
}
