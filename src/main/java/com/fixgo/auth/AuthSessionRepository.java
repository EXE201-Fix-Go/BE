package com.fixgo.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    @Query("select s.user.id from AuthSession s where s.id = :id")
    Optional<UUID> findUserIdById(UUID id);

    @EntityGraph(attributePaths = "user")
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findWithUserById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> lockById(UUID id);

    @Modifying(flushAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null")
    int revokeAllForUser(UUID userId, Instant now);
}
