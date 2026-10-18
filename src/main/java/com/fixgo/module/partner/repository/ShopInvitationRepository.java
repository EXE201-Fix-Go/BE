package com.fixgo.module.partner.repository;

import com.fixgo.module.partner.entity.ShopInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopInvitationRepository extends JpaRepository<ShopInvitation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ShopInvitation i where i.id = :id")
    Optional<ShopInvitation> lockById(UUID id);

    List<ShopInvitation> findByShopIdOrderByCreatedAtDesc(UUID shopId);

    List<ShopInvitation> findByInviteePhoneAndStatusOrderByCreatedAtDesc(String inviteePhone, ShopInvitation.Status status);

    boolean existsByShopIdAndInviteePhoneAndStatus(UUID shopId, String inviteePhone, ShopInvitation.Status status);

    long countByShopIdAndStatus(UUID shopId, ShopInvitation.Status status);
}
