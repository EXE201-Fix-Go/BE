package com.fixgo.partner;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** partner_documents — KYC evidence lives in private storage; only the key is stored here (BRD §8). */
@Entity
@Table(name = "partner_documents")
public class PartnerDocument {
    @Id
    private UUID id;
    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;
    @Column(name = "document_type", nullable = false, length = 30)
    private String documentType;
    @Column(name = "storage_key", nullable = false)
    private String storageKey;
    @Column(name = "review_status", nullable = false, length = 10)
    private String reviewStatus;
    @Column(name = "reviewed_by")
    private UUID reviewedBy;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PartnerDocument() { }

    public PartnerDocument(UUID partnerId, String documentType, String storageKey, Instant now) {
        this.id = UUID.randomUUID();
        this.partnerId = partnerId;
        this.documentType = documentType;
        this.storageKey = storageKey;
        this.reviewStatus = "PENDING";
        this.createdAt = now;
    }

    public void review(String status, UUID by, Instant now) { reviewStatus = status; reviewedBy = by; reviewedAt = now; }

    public UUID getId() { return id; }
    public String getDocumentType() { return documentType; }
    public String getReviewStatus() { return reviewStatus; }
}
