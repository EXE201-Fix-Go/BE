package com.fixgo.config;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "call_out_fee_configs")
public class CallOutFeeConfig {
    @Id
    private UUID id;
    @Column(name = "scope_type", nullable = false, length = 10)
    private String scopeType;
    @Column(name = "scope_value", length = 50)
    private String scopeValue;
    @Column(name = "fee_amount", nullable = false)
    private BigDecimal feeAmount;
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
    @Column(name = "effective_to")
    private Instant effectiveTo;
    @Column(name = "created_by")
    private UUID createdBy;

    protected CallOutFeeConfig() { }

    public UUID getId() { return id; }
    public BigDecimal getFeeAmount() { return feeAmount; }
}
