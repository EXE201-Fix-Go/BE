package com.fixgo.partner;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * partner_profiles (ERD §5). Location is written as lat/lng; a DB trigger keeps the geography column
 * in sync so ST_DWithin candidate searches never depend on JPA spatial mapping.
 */
@Entity
@Table(name = "partner_profiles")
public class PartnerProfile {
    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "parent_shop_id")
    private UUID parentShopId;
    @Enumerated(EnumType.STRING)
    @Column(name = "partner_type", nullable = false, length = 15)
    private PartnerType partnerType;
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 10)
    private VerificationStatus verificationStatus;
    @Column(name = "verified_by")
    private UUID verifiedBy;
    @Column(name = "verified_at")
    private Instant verifiedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Availability availability;
    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 20)
    private OperationalStatus operationalStatus;
    @Column(name = "operational_status_until")
    private Instant operationalStatusUntil;
    @Column(name = "shop_name", length = 120)
    private String shopName;
    @Column(name = "current_lat")
    private Double currentLat;
    @Column(name = "current_lng")
    private Double currentLng;
    @Column(name = "location_updated_at")
    private Instant locationUpdatedAt;

    protected PartnerProfile() { }

    public PartnerProfile(UUID userId, PartnerType partnerType, UUID parentShopId, String shopName) {
        if ((partnerType == PartnerType.SHOP_STAFF) != (parentShopId != null)) {
            throw new IllegalArgumentException("SHOP_STAFF requires a parent shop and vice versa (RB-10).");
        }
        this.userId = userId;
        this.partnerType = partnerType;
        this.parentShopId = parentShopId;
        this.shopName = shopName;
        this.verificationStatus = VerificationStatus.PENDING;
        this.availability = Availability.OFFLINE;
        this.operationalStatus = OperationalStatus.ACTIVE;
    }

    public boolean canTakeOrders() {
        return verificationStatus == VerificationStatus.APPROVED && operationalStatus != OperationalStatus.SUSPENDED;
    }
    public void verify(VerificationStatus status, UUID by, Instant now) {
        verificationStatus = status;
        verifiedBy = by;
        verifiedAt = now;
    }
    public void updatePresence(Availability availability, Double lat, Double lng, Instant now) {
        this.availability = availability;
        if (lat != null && lng != null) {
            this.currentLat = lat;
            this.currentLng = lng;
            this.locationUpdatedAt = now;
        }
    }
    public void setAvailability(Availability availability) { this.availability = availability; }

    public UUID getUserId() { return userId; }
    public UUID getParentShopId() { return parentShopId; }
    public PartnerType getPartnerType() { return partnerType; }
    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public Availability getAvailability() { return availability; }
    public OperationalStatus getOperationalStatus() { return operationalStatus; }
    public String getShopName() { return shopName; }
    public Double getCurrentLat() { return currentLat; }
    public Double getCurrentLng() { return currentLng; }
    public Instant getLocationUpdatedAt() { return locationUpdatedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
