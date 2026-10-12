package com.fixgo.module.partner.repository;
import com.fixgo.module.partner.entity.*;
import com.fixgo.module.partner.enums.*;
import com.fixgo.module.partner.dto.*;
import com.fixgo.module.partner.repository.*;
import com.fixgo.module.partner.service.*;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PartnerDocumentRepository extends JpaRepository<PartnerDocument, UUID> {
    List<PartnerDocument> findByPartnerIdOrderByCreatedAtAsc(UUID partnerId);
}
