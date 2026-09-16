package com.fixgo.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    @Query("select t.session.id from RefreshToken t where t.tokenHash = :hash")
    Optional<UUID> findSessionIdByTokenHash(String hash);
    Optional<RefreshToken> findByTokenHash(String hash);
}
