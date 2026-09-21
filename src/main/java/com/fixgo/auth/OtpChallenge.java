package com.fixgo.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** otp_challenges — only the hash is stored; a code is usable exactly once (RB-03). */
@Entity
@Table(name = "otp_challenges")
public class OtpChallenge {
    @Id
    private UUID id;
    @Column(nullable = false, length = 20)
    private String target;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OtpPurpose purpose;
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;
    @Column(name = "attempt_count", nullable = false)
    private short attemptCount;
    @Column(name = "max_attempts", nullable = false)
    private short maxAttempts;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @Column(name = "request_ip", length = 45)
    private String requestIp;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OtpChallenge() { }

    public OtpChallenge(UUID id, String target, OtpPurpose purpose, String codeHash, int maxAttempts,
                        Instant now, Instant expiresAt, String requestIp) {
        this.id = id;
        this.target = target;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.maxAttempts = (short) maxAttempts;
        this.createdAt = now;
        this.expiresAt = expiresAt;
        this.requestIp = requestIp;
    }

    public boolean isOpen(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now) && attemptCount < maxAttempts;
    }
    public void recordFailure() { attemptCount++; }
    public void consume(Instant now) { consumedAt = now; }

    public UUID getId() { return id; }
    public String getTarget() { return target; }
    public OtpPurpose getPurpose() { return purpose; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttemptCount() { return attemptCount; }
}
