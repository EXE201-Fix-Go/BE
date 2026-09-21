package com.fixgo.quote;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** quotes — each revision carries the whole order value (RB-42); SENT/APPROVED rows are immutable (RB-44). */
@Entity
@Table(name = "quotes")
public class Quote {
    public enum Type { INITIAL, ADDITIONAL }
    public enum Status { DRAFT, SENT, APPROVED, DECLINED, SUPERSEDED, EXPIRED }

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "created_by_partner_id", nullable = false)
    private UUID createdByPartnerId;
    @Column(name = "revision_no", nullable = false)
    private int revisionNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "quote_type", nullable = false, length = 10)
    private Type quoteType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;
    @Column(name = "call_out_fee_amount", nullable = false)
    private BigDecimal callOutFeeAmount;
    @Column(name = "labor_amount", nullable = false)
    private BigDecimal laborAmount;
    @Column(name = "parts_amount", nullable = false)
    private BigDecimal partsAmount;
    @Column(name = "surcharge_amount", nullable = false)
    private BigDecimal surchargeAmount;
    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;
    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "valid_until")
    private Instant validUntil;
    @Column(name = "decided_by")
    private UUID decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decline_reason")
    private String declineReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo asc")
    private List<QuoteItem> items = new ArrayList<>();

    protected Quote() { }

    public Quote(UUID orderId, UUID partnerId, int revisionNo, Type type, BigDecimal callOutFee, Instant now,
                 Instant validUntil) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.createdByPartnerId = partnerId;
        this.revisionNo = revisionNo;
        this.quoteType = type;
        this.status = Status.DRAFT;
        this.callOutFeeAmount = callOutFee;
        this.laborAmount = BigDecimal.ZERO;
        this.partsAmount = BigDecimal.ZERO;
        this.surchargeAmount = BigDecimal.ZERO;
        this.discountAmount = BigDecimal.ZERO;
        this.totalAmount = callOutFee;
        this.createdAt = now;
        this.validUntil = validUntil;
    }

    public void addItem(QuoteItem.Type type, String description, BigDecimal quantity, BigDecimal unitPrice, UUID serviceId) {
        var item = new QuoteItem(this, items.size() + 1, type, description, quantity, unitPrice, serviceId);
        items.add(item);
        switch (type) {
            case LABOR, SUPPORT -> laborAmount = laborAmount.add(item.getLineAmount());
            case PART -> partsAmount = partsAmount.add(item.getLineAmount());
            case SURCHARGE -> surchargeAmount = surchargeAmount.add(item.getLineAmount());
            case DISCOUNT -> discountAmount = discountAmount.add(item.getLineAmount());
        }
        // RB-47
        totalAmount = callOutFeeAmount.add(laborAmount).add(partsAmount).add(surchargeAmount).subtract(discountAmount);
    }

    public void send(Instant now) { status = Status.SENT; sentAt = now; }
    public void approve(UUID by, Instant now) { status = Status.APPROVED; decidedBy = by; decidedAt = now; }
    public void decline(UUID by, String reason, Instant now) {
        status = Status.DECLINED; decidedBy = by; decidedAt = now; declineReason = reason;
    }
    public void supersede() { status = Status.SUPERSEDED; }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getCreatedByPartnerId() { return createdByPartnerId; }
    public int getRevisionNo() { return revisionNo; }
    public Type getQuoteType() { return quoteType; }
    public Status getStatus() { return status; }
    public BigDecimal getCallOutFeeAmount() { return callOutFeeAmount; }
    public BigDecimal getLaborAmount() { return laborAmount; }
    public BigDecimal getPartsAmount() { return partsAmount; }
    public BigDecimal getSurchargeAmount() { return surchargeAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Instant getSentAt() { return sentAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDeclineReason() { return declineReason; }
    public List<QuoteItem> getItems() { return items; }
}
