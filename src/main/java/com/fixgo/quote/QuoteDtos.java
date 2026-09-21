package com.fixgo.quote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class QuoteDtos {
    private QuoteDtos() { }

    /** POST /orders/{id}/quotes — the partner's quote editor sends the whole order value each time (RB-42). */
    public record CreateQuoteRequest(@NotEmpty @Valid List<ItemRequest> items,
                                     @Min(5) @Max(1440) Integer validMinutes) { }

    public record ItemRequest(@NotNull QuoteItem.Type itemType,
                              @NotBlank @Size(max = 200) String description,
                              @NotNull @DecimalMin("0.01") BigDecimal quantity,
                              @NotNull @DecimalMin("0") BigDecimal unitPrice,
                              String serviceId) { }

    public record DeclineRequest(@Size(max = 500) String reason) { }

    public record ItemResponse(UUID id, int lineNo, QuoteItem.Type itemType, String description, BigDecimal quantity,
                               BigDecimal unitPrice, BigDecimal lineAmount, String serviceId) { }

    public record QuoteResponse(UUID id, UUID orderId, int revisionNo, Quote.Type quoteType, Quote.Status status,
                                BigDecimal callOutFeeAmount, BigDecimal laborAmount, BigDecimal partsAmount,
                                BigDecimal surchargeAmount, BigDecimal discountAmount, BigDecimal totalAmount,
                                Instant sentAt, Instant decidedAt, String declineReason, List<ItemResponse> items) { }
}
