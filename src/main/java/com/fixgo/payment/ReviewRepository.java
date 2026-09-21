package com.fixgo.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {
    Optional<Review> findByOrderId(UUID orderId);
    boolean existsByOrderId(UUID orderId);

    @org.springframework.data.jpa.repository.Query(
            "select avg(r.rating), count(r) from Review r where r.partnerId = :partnerId and r.hidden = false")
    Object[] ratingSummary(UUID partnerId);
}
