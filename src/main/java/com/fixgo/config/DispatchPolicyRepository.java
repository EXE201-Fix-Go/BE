package com.fixgo.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DispatchPolicyRepository extends JpaRepository<DispatchPolicy, UUID> {
    @Query("""
            select p from DispatchPolicy p
            where p.scopeType = 'GLOBAL' and p.roundNo = :roundNo and p.effectiveFrom <= :now
              and (p.effectiveTo is null or p.effectiveTo > :now)
            """)
    Optional<DispatchPolicy> findActiveGlobalRound(int roundNo, Instant now);
}
