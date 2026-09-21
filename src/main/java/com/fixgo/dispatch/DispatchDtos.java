package com.fixgo.dispatch;

import com.fixgo.order.OrderStatus;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class DispatchDtos {
    private DispatchDtos() { }

    public record DeclineRequest(@Size(max = 300) String reason) { }

    /** What a partner sees on the "new job" card. */
    public record OfferResponse(UUID assignmentId, UUID orderId, String orderCode, OrderStatus orderStatus,
                                String serviceId, String serviceName, String addressText, String note,
                                Double lat, Double lng, BigDecimal callOutFee, String contactName,
                                Instant offeredAt, Instant expiresAt, int roundNo) { }
}
