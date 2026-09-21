package com.fixgo.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** user_identities — how a user proves who they are (RB-02). For phone: provider_uid is E.164. */
@Entity
@Table(name = "user_identities")
public class UserIdentity {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IdentityProvider provider;
    @Column(name = "provider_uid", nullable = false, length = 100)
    private String providerUid;
    @Column(name = "verified_at")
    private Instant verifiedAt;
    @Column(name = "is_primary", nullable = false)
    private boolean primary;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserIdentity() { }

    public UserIdentity(User user, IdentityProvider provider, String providerUid, boolean primary, Instant now,
                        Instant verifiedAt) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.provider = provider;
        this.providerUid = providerUid;
        this.primary = primary;
        this.createdAt = now;
        this.verifiedAt = verifiedAt;
    }

    public void markVerified(Instant now) { if (verifiedAt == null) verifiedAt = now; }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public IdentityProvider getProvider() { return provider; }
    public String getProviderUid() { return providerUid; }
    public boolean isPrimary() { return primary; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
