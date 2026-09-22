package com.fixgo.dispatch;

import com.fixgo.order.OrderStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

public final class DispatchDtos {
    private DispatchDtos() { }

    public record DeclineRequest(@Size(max = 300) String reason) { }

    /** Vị trí GPS hiện tại của thợ lúc bấm nhận đơn — làm mốc tính phí di chuyển. */
    public record AcceptRequest(@DecimalMin("-90") @DecimalMax("90") Double lat,
                                @DecimalMin("-180") @DecimalMax("180") Double lng) { }

    /** What a partner sees on the "new job" card. */
    public record OfferResponse(UUID assignmentId, UUID orderId, String orderCode, OrderStatus orderStatus,
                                String serviceId, String serviceName, String addressText, String note,
                                Double lat, Double lng, BigDecimal callOutFee, String contactName,
                                Instant offeredAt, Instant expiresAt, int roundNo, List<String> photoUrls) { }
}
