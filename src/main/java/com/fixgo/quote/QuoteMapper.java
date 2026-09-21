package com.fixgo.quote;

import com.fixgo.catalog.ServiceCatalog;
import com.fixgo.catalog.ServiceCatalogRepository;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class QuoteMapper {
    private final ServiceCatalogRepository catalog;

    public QuoteMapper(ServiceCatalogRepository catalog) { this.catalog = catalog; }

    public QuoteDtos.QuoteResponse toResponse(Quote q) {
        var serviceIds = q.getItems().stream().map(QuoteItem::getServiceId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> codes = serviceIds.isEmpty() ? Map.of() : catalog.findAllById(serviceIds).stream()
                .collect(Collectors.toMap(ServiceCatalog::getId, ServiceCatalog::getCode));
        var items = q.getItems().stream().map(i -> new QuoteDtos.ItemResponse(i.getId(), i.getLineNo(),
                i.getItemType(), i.getDescription(), i.getQuantity(), i.getUnitPrice(), i.getLineAmount(),
                i.getServiceId() == null ? null : codes.get(i.getServiceId())))
                .toList();
        return new QuoteDtos.QuoteResponse(q.getId(), q.getOrderId(), q.getRevisionNo(), q.getQuoteType(),
                q.getStatus(), q.getCallOutFeeAmount(), q.getLaborAmount(), q.getPartsAmount(),
                q.getSurchargeAmount(), q.getDiscountAmount(), q.getTotalAmount(), q.getSentAt(), q.getDecidedAt(),
                q.getDeclineReason(), items);
    }
}
