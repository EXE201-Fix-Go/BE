package com.fixgo.partner;

import jakarta.persistence.*;
import java.util.UUID;

/** partner_services — which catalogue services a partner offers (used by dispatch matching). */
@Entity
@Table(name = "partner_services")
public class PartnerServiceOffer {
    @Id
    private UUID id;
    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;
    @Column(name = "service_id", nullable = false)
    private UUID serviceId;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected PartnerServiceOffer() { }

    public PartnerServiceOffer(UUID partnerId, UUID serviceId) {
        this.id = UUID.randomUUID();
        this.partnerId = partnerId;
        this.serviceId = serviceId;
    }

    public UUID getServiceId() { return serviceId; }
}
