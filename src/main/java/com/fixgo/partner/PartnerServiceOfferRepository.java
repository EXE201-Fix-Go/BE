package com.fixgo.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PartnerServiceOfferRepository extends JpaRepository<PartnerServiceOffer, UUID> {
    List<PartnerServiceOffer> findByPartnerId(UUID partnerId);
    void deleteByPartnerId(UUID partnerId);
}
