package com.fixgo.dispatch;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** dispatch_rounds — one broadcast wave with its radius and deadline (BR07/BR08). */
@Entity
@Table(name = "dispatch_rounds")
public class DispatchRound {
    public enum EndReason { CLAIMED, TIMEOUT, CANCELLED, NO_CANDIDATE }
    /** RB-35 */
    public enum NoCandidateReason { NO_PARTNER_ONLINE, OUT_OF_RADIUS, ALL_BUSY, ALL_SUSPENDED, NO_SERVICE_MATCH }

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "policy_id", nullable = false)
    private UUID policyId;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Column(name = "radius_m", nullable = false)
    private int radiusM;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 15)
    private EndReason endReason;
    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;
    @Enumerated(EnumType.STRING)
    @Column(name = "no_candidate_reason", length = 20)
    private NoCandidateReason noCandidateReason;

    protected DispatchRound() { }

    public DispatchRound(UUID orderId, UUID policyId, int roundNo, int radiusM, Instant now, Instant expiresAt,
                         int candidateCount, NoCandidateReason noCandidateReason) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.policyId = policyId;
        this.roundNo = roundNo;
        this.radiusM = radiusM;
        this.startedAt = now;
        this.expiresAt = expiresAt;
        this.candidateCount = candidateCount;
        this.noCandidateReason = candidateCount == 0 ? noCandidateReason : null;
    }

    public boolean isOpen() { return endedAt == null; }
    public void end(EndReason reason, Instant now) { if (endedAt == null) { endedAt = now; endReason = reason; } }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getPolicyId() { return policyId; }
    public int getRoundNo() { return roundNo; }
    public int getRadiusM() { return radiusM; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getCandidateCount() { return candidateCount; }
    public EndReason getEndReason() { return endReason; }
}
