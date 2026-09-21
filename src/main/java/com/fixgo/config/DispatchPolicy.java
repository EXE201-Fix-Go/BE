package com.fixgo.config;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** dispatch_policies — radius/timeout per broadcast round (BR08, RB-26). */
@Entity
@Table(name = "dispatch_policies")
public class DispatchPolicy {
    @Id
    private UUID id;
    @Column(name = "scope_type", nullable = false, length = 10)
    private String scopeType;
    @Column(name = "scope_value", length = 50)
    private String scopeValue;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Column(name = "radius_m", nullable = false)
    private int radiusM;
    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;
    @Column(name = "include_lower_priority", nullable = false)
    private boolean includeLowerPriority;
    @Column(name = "is_final_round", nullable = false)
    private boolean finalRound;
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
    @Column(name = "effective_to")
    private Instant effectiveTo;

    protected DispatchPolicy() { }

    public UUID getId() { return id; }
    public int getRoundNo() { return roundNo; }
    public int getRadiusM() { return radiusM; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public boolean isIncludeLowerPriority() { return includeLowerPriority; }
    public boolean isFinalRound() { return finalRound; }
}
