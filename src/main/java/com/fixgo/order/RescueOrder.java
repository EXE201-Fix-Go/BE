package com.fixgo.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** rescue_orders (ERD §7). Status changes only go through {@link OrderStateMachine}. */
@Entity
@Table(name = "rescue_orders")
public class RescueOrder {
    @Id
    private UUID id;
    @Column(name = "order_code", nullable = false, unique = true, length = 20)
    private String orderCode;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "vehicle_id")
    private UUID vehicleId;
    @Column(name = "requested_service_id", nullable = false)
    private UUID requestedServiceId;
    @Column(name = "vehicle_description_snapshot")
    private String vehicleDescriptionSnapshot;
    @Column(name = "contact_name", length = 100)
    private String contactName;
    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;
    @Column(name = "pickup_lat", nullable = false)
    private double pickupLat;
    @Column(name = "pickup_lng", nullable = false)
    private double pickupLng;
    @Column(name = "pickup_address_text", nullable = false)
    private String pickupAddressText;
    @Column(name = "pickup_note")
    private String pickupNote;
    @Column(name = "call_out_fee_snapshot", nullable = false)
    private BigDecimal callOutFeeSnapshot;
    @Column(name = "call_out_fee_config_id", nullable = false)
    private UUID callOutFeeConfigId;
    @Column(name = "call_out_fee_confirmed_at")
    private Instant callOutFeeConfirmedAt;
    /** Straight-line km from the accepting partner to the pickup, snapshotted at accept time (nullable until then). */
    @Column(name = "travel_distance_km")
    private BigDecimal travelDistanceKm;
    @Column(name = "travel_fee_snapshot")
    private BigDecimal travelFeeSnapshot;
    @Column(name = "travel_fee_config_id")
    private UUID travelFeeConfigId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private OrderStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "cancelled_by")
    private UUID cancelledBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_source", length = 10)
    private ActorType cancellationSource;
    @Column(name = "cancellation_reason")
    private String cancellationReason;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Version
    @Column(nullable = false)
    private long version;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_extra_services", joinColumns = @JoinColumn(name = "order_id"))
    @Column(name = "service_id", nullable = false)
    private Set<UUID> extraServiceIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "order_photos", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    @Column(name = "url", nullable = false)
    private List<String> photoUrls = new ArrayList<>();

    protected RescueOrder() { }

    public RescueOrder(String orderCode, UUID customerId, UUID requestedServiceId, String contactName,
                       String contactPhone, double pickupLat, double pickupLng, String pickupAddressText,
                       String pickupNote, String vehicleDescription, BigDecimal callOutFee, UUID callOutFeeConfigId,
                       Instant now) {
        this.id = UUID.randomUUID();
        this.orderCode = orderCode;
        this.customerId = customerId;
        this.requestedServiceId = requestedServiceId;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.pickupLat = pickupLat;
        this.pickupLng = pickupLng;
        this.pickupAddressText = pickupAddressText;
        this.pickupNote = pickupNote;
        this.vehicleDescriptionSnapshot = vehicleDescription;
        this.callOutFeeSnapshot = callOutFee;
        this.callOutFeeConfigId = callOutFeeConfigId;
        this.status = OrderStatus.PENDING_CONFIRMATION;   // RB-27
        this.createdAt = now;
    }

    /** Package-private: only the state machine may move the status. */
    void moveTo(OrderStatus next) { this.status = next; }

    public void confirmCallOutFee(Instant now) { callOutFeeConfirmedAt = now; confirmedAt = now; }
    /** Snapshotted once when a partner accepts (RB-23: rate comes from travel_fee_configs, not code). */
    public void recordTravel(BigDecimal distanceKm, BigDecimal fee, UUID configId) {
        this.travelDistanceKm = distanceKm;
        this.travelFeeSnapshot = fee;
        this.travelFeeConfigId = configId;
    }
    public void markCompleted(Instant now) { completedAt = now; }
    public void recordCancellation(UUID by, ActorType source, String reason, Instant now) {
        cancelledBy = by;
        cancellationSource = source;
        cancellationReason = reason;
        cancelledAt = now;
    }

    public UUID getId() { return id; }
    public String getOrderCode() { return orderCode; }
    public UUID getCustomerId() { return customerId; }
    public UUID getRequestedServiceId() { return requestedServiceId; }
    public String getVehicleDescriptionSnapshot() { return vehicleDescriptionSnapshot; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public double getPickupLat() { return pickupLat; }
    public double getPickupLng() { return pickupLng; }
    public String getPickupAddressText() { return pickupAddressText; }
    public String getPickupNote() { return pickupNote; }
    public BigDecimal getCallOutFeeSnapshot() { return callOutFeeSnapshot; }
    public Instant getCallOutFeeConfirmedAt() { return callOutFeeConfirmedAt; }
    public BigDecimal getTravelDistanceKm() { return travelDistanceKm; }
    public BigDecimal getTravelFeeSnapshot() { return travelFeeSnapshot; }
    public UUID getTravelFeeConfigId() { return travelFeeConfigId; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public ActorType getCancellationSource() { return cancellationSource; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCancelledAt() { return cancelledAt; }
    public long getVersion() { return version; }
    public Set<UUID> getExtraServiceIds() { return extraServiceIds; }
    public List<String> getPhotoUrls() { return photoUrls; }
}
