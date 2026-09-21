package com.fixgo.user;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {
    @EntityGraph(attributePaths = "user")
    Optional<UserIdentity> findByProviderAndProviderUid(IdentityProvider provider, String providerUid);

    @Query("select i.providerUid from UserIdentity i where i.user.id = :userId and i.primary = true")
    Optional<String> findPrimaryUid(UUID userId);
}
