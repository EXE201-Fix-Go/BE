package com.fixgo.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PartnerDocumentRepository extends JpaRepository<PartnerDocument, UUID> {
    List<PartnerDocument> findByPartnerIdOrderByCreatedAtAsc(UUID partnerId);
}
