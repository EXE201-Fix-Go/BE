package com.fixgo.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId and p.status = :status")
    Optional<Payment> lockByOrderIdAndStatus(UUID orderId, Payment.Status status);

    boolean existsByIdempotencyKey(String key);
}
