package com.fixgo.quote;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Entity
@Table(name = "quote_items")
public class QuoteItem {
    public enum Type { LABOR, PART, SURCHARGE, DISCOUNT, SUPPORT }

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;
    @Column(name = "service_id")
    private UUID serviceId;
    @Column(name = "line_no", nullable = false)
    private int lineNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 10)
    private Type itemType;
    @Column(nullable = false, length = 200)
    private String description;
    @Column(nullable = false)
    private BigDecimal quantity;
    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;
    @Column(name = "line_amount", nullable = false)
    private BigDecimal lineAmount;

    protected QuoteItem() { }

    QuoteItem(Quote quote, int lineNo, Type type, String description, BigDecimal quantity, BigDecimal unitPrice,
              UUID serviceId) {
        this.id = UUID.randomUUID();
        this.quote = quote;
        this.lineNo = lineNo;
        this.itemType = type;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.serviceId = serviceId;
        this.lineAmount = quantity.multiply(unitPrice).setScale(0, RoundingMode.HALF_UP);
    }

    public UUID getId() { return id; }
    public int getLineNo() { return lineNo; }
    public Type getItemType() { return itemType; }
    public String getDescription() { return description; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineAmount() { return lineAmount; }
    public UUID getServiceId() { return serviceId; }
}
