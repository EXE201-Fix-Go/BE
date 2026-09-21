package com.fixgo.partner;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerProfileRepository extends JpaRepository<PartnerProfile, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PartnerProfile p where p.userId = :id")
    Optional<PartnerProfile> lockById(UUID id);

    List<PartnerProfile> findByParentShopIdOrderByUserId(UUID parentShopId);

    /**
     * Broadcast candidates (BRD §7 matching): approved, online, not suspended, offering the service,
     * inside the round radius. PostGIS does the distance work on the geography columns.
     */
    @Query(value = """
            select p.user_id
            from partner_profiles p
            join app_users u on u.id = p.user_id
            where p.verification_status = 'APPROVED'
              and p.availability = 'ONLINE'
              and u.status = 'ACTIVE'
              and p.operational_status <> 'SUSPENDED'
              and (:includeLowerPriority = true or p.operational_status = 'ACTIVE')
              and p.current_location is not null
              and ST_DWithin(p.current_location,
                             ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusM)
              and exists (select 1 from partner_services ps
                          where ps.partner_id = p.user_id and ps.service_id = :serviceId and ps.is_active)
            order by ST_Distance(p.current_location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
            """, nativeQuery = true)
    List<UUID> findCandidates(double lat, double lng, int radiusM, UUID serviceId, boolean includeLowerPriority);

    @Query(value = """
            select count(*) from partner_profiles p join app_users u on u.id = p.user_id
            where p.verification_status = 'APPROVED' and p.availability = 'ONLINE' and u.status = 'ACTIVE'
              and p.operational_status <> 'SUSPENDED' and p.current_location is not null
            """, nativeQuery = true)
    long countOnlineEligible();

    @Query(value = """
            select count(*) from partner_profiles p join app_users u on u.id = p.user_id
            where p.verification_status = 'APPROVED' and p.availability = 'ONLINE' and u.status = 'ACTIVE'
              and p.operational_status <> 'SUSPENDED' and p.current_location is not null
              and ST_DWithin(p.current_location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusM)
            """, nativeQuery = true)
    long countOnlineWithinRadius(double lat, double lng, int radiusM);
}
