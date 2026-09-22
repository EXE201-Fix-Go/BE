package com.fixgo.config;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** travel_fee_configs (RB-23): per-km rescue travel rate lives in the DB, never as a constant in code. */
@Entity
@Table(name = "travel_fee_configs")
public class TravelFeeConfig {
    @Id
    private UUID id;
    @Column(name = "scope_type", nullable = false, length = 10)
    private String scopeType;
    @Column(name = "scope_value", length = 50)
    private String scopeValue;
    @Column(name = "per_km_amount", nullable = false)
    private BigDecimal perKmAmount;
    /** Kilometres billed at zero before the per-km rate applies. */
    @Column(name = "free_km", nullable = false)
    private BigDecimal freeKm;
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
    @Column(name = "effective_to")
    private Instant effectiveTo;
    @Column(name = "created_by")
    private UUID createdBy;

    protected TravelFeeConfig() { }

    public UUID getId() { return id; }
    public BigDecimal getPerKmAmount() { return perKmAmount; }
    public BigDecimal getFreeKm() { return freeKm; }
}
