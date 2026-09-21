package com.fixgo.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {
    Optional<Review> findByOrderId(UUID orderId);
    boolean existsByOrderId(UUID orderId);
}
