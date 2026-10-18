package com.fixgo.module.order.dto;
import com.fixgo.module.order.entity.*;
import com.fixgo.module.order.enums.*;
import com.fixgo.module.order.dto.*;
import com.fixgo.module.order.repository.*;
import com.fixgo.module.order.service.*;


import com.fixgo.module.order.dto.QuoteDtos;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos() { }

    /** POST /orders — matches FE CreateOrderInput plus the pickup coordinates the map provides. */
    public record CreateOrderRequest(
            @NotBlank String serviceId,
            List<@NotBlank String> extraServiceIds,
            @NotBlank @Size(max = 500) String addressText,
            @Size(max = 500) String note,
            @Size(max = 6) List<@NotBlank @Size(max = 1000) String> photoUrls,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @Size(max = 120) String vehicleDescription,
            @Size(max = 100) String contactName,
            @Size(max = 20) String contactPhone) { }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) { }

    public record PartnerSummary(UUID id, String fullName, String phone, Double lat, Double lng,
                                 Instant acceptedAt, Instant arrivedAt) { }

    public record PaymentSummary(UUID id, BigDecimal amount, String status, String method, Instant confirmedAt) { }

    /** GET /orders/{id}/status — cheap polling payload. */
    public record OrderStatusResponse(UUID id, String orderCode, OrderStatus status, long version) { }

    public record HistoryEntry(OrderStatus from, OrderStatus to, ActorType actorType, String note, Instant at) { }

    /** GET /orders/{id} — superset of the FE Order type. */
    public record OrderResponse(UUID id, String orderCode, OrderStatus status, String serviceId, String serviceName,
                                List<String> extraServiceIds, String addressText, String note, List<String> photoUrls,
                                String contactName, String contactPhone,
                                Double lat, Double lng, BigDecimal callOutFee, BigDecimal travelDistanceKm,
                                BigDecimal travelFee, Instant createdAt, Instant confirmedAt,
                                Instant completedAt, PartnerSummary partner, QuoteDtos.QuoteResponse quote,
                                PaymentSummary payment, ActorType cancellationSource, String cancellationReason,
                                Instant cancelledAt, List<HistoryEntry> history) {

        /** S1: once the order ends the customer no longer sees the partner's phone or position. */
        public OrderResponse withoutPartnerContact() {
            if (partner == null) return this;
            var hidden = new PartnerSummary(partner.id(), partner.fullName(), null, null, null,
                    partner.acceptedAt(), partner.arrivedAt());
            return new OrderResponse(id, orderCode, status, serviceId, serviceName, extraServiceIds, addressText, note,
                    photoUrls, contactName, contactPhone, lat, lng, callOutFee, travelDistanceKm, travelFee, createdAt,
                    confirmedAt, completedAt, hidden, quote, payment, cancellationSource, cancellationReason,
                    cancelledAt, history);
        }

        /** S1: once the order ends the partner no longer sees the customer's phone or exact pickup point. */
        public OrderResponse withoutCustomerContact() {
            return new OrderResponse(id, orderCode, status, serviceId, serviceName, extraServiceIds, addressText, note,
                    photoUrls, contactName, null, null, null, callOutFee, travelDistanceKm, travelFee, createdAt,
                    confirmedAt, completedAt, partner, quote, payment, cancellationSource, cancellationReason,
                    cancelledAt, history);
        }
    }
}
