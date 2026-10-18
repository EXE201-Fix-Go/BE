package com.fixgo.module.order.dto;

import com.fixgo.module.order.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CustomerOrderReadDtos {
    private CustomerOrderReadDtos() { }

    /** List cards only; contact details, photos, quote lines and audit history stay on GET /orders/{id}. */
    public record HistoryItem(UUID id, String orderCode, OrderStatus status, String serviceId,
                              String serviceName, String addressText, Instant createdAt,
                              String partnerName, BigDecimal totalAmount) { }

    public record HistoryPage(List<HistoryItem> items, int page, int size, boolean hasNext) { }
}
