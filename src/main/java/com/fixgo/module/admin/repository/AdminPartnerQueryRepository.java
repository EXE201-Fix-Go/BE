package com.fixgo.module.admin.repository;

import com.fixgo.module.partner.entity.PartnerProfile;
import com.fixgo.module.partner.enums.Availability;
import com.fixgo.module.partner.enums.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read-only partner/KYC queries for the admin dashboard. */
public interface AdminPartnerQueryRepository extends Repository<PartnerProfile, UUID> {

    /** Columns: verification status, count. */
    @Query("select p.verificationStatus, count(p) from PartnerProfile p group by p.verificationStatus")
    List<Object[]> countByVerification();

    @Query("select count(p) from PartnerProfile p "
            + "where p.verificationStatus = :verification and p.availability = :availability")
    long countByVerificationAndAvailability(@Param("verification") VerificationStatus verification,
                                            @Param("availability") Availability availability);

    /** Newest registrations first, so the KYC queue shows people who applied most recently on top. */
    @Query(value = """
            select p from PartnerProfile p join User u on u.id = p.userId
            order by u.createdAt desc, p.userId asc
            """, countQuery = "select count(p) from PartnerProfile p")
    Page<PartnerProfile> pageNewestFirst(Pageable pageable);

    @Query(value = """
            select p from PartnerProfile p join User u on u.id = p.userId
            where p.verificationStatus = :verification
            order by u.createdAt desc, p.userId asc
            """, countQuery = "select count(p) from PartnerProfile p where p.verificationStatus = :verification")
    Page<PartnerProfile> pageNewestFirstByVerification(@Param("verification") VerificationStatus verification,
                                                       Pageable pageable);

    /** Columns: partnerId, documentType, reviewStatus — for a whole page in one query, oldest upload first. */
    @Query("select d.partnerId, d.documentType, d.reviewStatus from PartnerDocument d "
            + "where d.partnerId in :partnerIds order by d.createdAt asc")
    List<Object[]> documentsFor(@Param("partnerIds") Collection<UUID> partnerIds);
}
