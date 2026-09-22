package com.fixgo.order;

import com.fixgo.quote.QuoteDtos;
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
                                Instant cancelledAt, List<HistoryEntry> history) { }
}
