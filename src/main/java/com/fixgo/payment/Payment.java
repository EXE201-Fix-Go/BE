package com.fixgo.payment;

import com.fixgo.order.ActorType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** payments — pilot scope: cash held by the partner, confirmed by a "Đã thanh toán" tap (BRD §9, RB-59). */
@Entity
@Table(name = "payments")
public class Payment {
    public enum Method { CASH, BANK_TRANSFER, GATEWAY }
    public enum FundHolder { PARTNER, PLATFORM }
    public enum Status { PENDING, CONFIRMED, FAILED }

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "quote_id")
    private UUID quoteId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Method method;
    @Enumerated(EnumType.STRING)
    @Column(name = "fund_holder", nullable = false, length = 10)
    private FundHolder fundHolder;
    @Column(name = "collected_by_partner_id")
    private UUID collectedByPartnerId;
    @Column(nullable = false)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;
    @Column(name = "confirmed_by")
    private UUID confirmedBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "confirmed_by_role", length = 10)
    private ActorType confirmedByRole;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 80)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() { }

    public Payment(UUID orderId, UUID quoteId, UUID partnerId, BigDecimal amount, String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.quoteId = quoteId;
        this.collectedByPartnerId = partnerId;
        this.amount = amount;
        this.method = Method.CASH;
        this.fundHolder = FundHolder.PARTNER;
        this.status = Status.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
    }

    public void confirm(UUID by, ActorType role, Instant now) {
        status = Status.CONFIRMED; confirmedBy = by; confirmedByRole = role; confirmedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getQuoteId() { return quoteId; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
    public Method getMethod() { return method; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
