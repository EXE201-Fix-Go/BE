package com.fixgo.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TravelFeeConfigRepository extends JpaRepository<TravelFeeConfig, UUID> {
    @Query("""
            select c from TravelFeeConfig c
            where c.scopeType = 'GLOBAL' and c.effectiveFrom <= :now
              and (c.effectiveTo is null or c.effectiveTo > :now)
            """)
    Optional<TravelFeeConfig> findActiveGlobal(Instant now);
}
