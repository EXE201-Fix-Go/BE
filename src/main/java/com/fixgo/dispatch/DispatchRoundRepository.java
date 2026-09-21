package com.fixgo.dispatch;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispatchRoundRepository extends JpaRepository<DispatchRound, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DispatchRound r where r.id = :id")
    Optional<DispatchRound> lockById(UUID id);

    List<DispatchRound> findByOrderIdAndEndedAtIsNull(UUID orderId);

    @Query("select r.id from DispatchRound r where r.endedAt is null and r.expiresAt <= :now")
    List<UUID> findExpiredOpenIds(Instant now);

    Optional<DispatchRound> findFirstByOrderIdOrderByRoundNoDesc(UUID orderId);
}
