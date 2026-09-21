package com.fixgo.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {
    @EntityGraph(attributePaths = "user")
    @Query("select d from UserDevice d where d.id = :id")
    Optional<UserDevice> findWithUserById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from UserDevice d where d.id = :id")
    Optional<UserDevice> lockById(UUID id);

    @Modifying(flushAutomatically = true)
    @Query("update UserDevice d set d.revokedAt = :now, d.revokedBy = :by where d.user.id = :userId and d.revokedAt is null")
    int revokeAllForUser(UUID userId, Instant now, UUID by);
}
