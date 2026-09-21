package com.fixgo.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/** services — the public price list. `code` is the stable id the FE sends (e.g. "tire-patch"). */
@Entity
@Table(name = "services")
public class ServiceCatalog {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 40)
    private String code;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 200)
    private String description;
    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice;
    @Column(name = "is_active", nullable = false)
    private boolean active;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ServiceCatalog() { }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getBasePrice() { return basePrice; }
    public boolean isActive() { return active; }
    public int getSortOrder() { return sortOrder; }
}
