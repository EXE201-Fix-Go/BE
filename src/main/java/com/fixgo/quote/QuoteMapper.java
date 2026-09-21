package com.fixgo.quote;

import com.fixgo.catalog.ServiceCatalogRepository;
import org.springframework.stereotype.Component;

@Component
public class QuoteMapper {
    private final ServiceCatalogRepository catalog;

    public QuoteMapper(ServiceCatalogRepository catalog) { this.catalog = catalog; }

    public QuoteDtos.QuoteResponse toResponse(Quote q) {
        var items = q.getItems().stream().map(i -> new QuoteDtos.ItemResponse(i.getId(), i.getLineNo(),
                i.getItemType(), i.getDescription(), i.getQuantity(), i.getUnitPrice(), i.getLineAmount(),
                i.getServiceId() == null ? null : catalog.findById(i.getServiceId()).map(s -> s.getCode()).orElse(null)))
                .toList();
        return new QuoteDtos.QuoteResponse(q.getId(), q.getOrderId(), q.getRevisionNo(), q.getQuoteType(),
                q.getStatus(), q.getCallOutFeeAmount(), q.getLaborAmount(), q.getPartsAmount(),
                q.getSurchargeAmount(), q.getDiscountAmount(), q.getTotalAmount(), q.getSentAt(), q.getDecidedAt(),
                q.getDeclineReason(), items);
    }
}
