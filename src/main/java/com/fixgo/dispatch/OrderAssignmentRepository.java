package com.fixgo.dispatch;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderAssignmentRepository extends JpaRepository<OrderAssignment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OrderAssignment a where a.id = :id")
    Optional<OrderAssignment> lockById(UUID id);

    List<OrderAssignment> findByDispatchRoundId(UUID roundId);

    List<OrderAssignment> findByOrderIdAndStatus(UUID orderId, OrderAssignment.Status status);

    /** The partner currently responsible for the order (at most one ACCEPTED at a time). */
    Optional<OrderAssignment> findFirstByOrderIdAndStatusOrderByAcceptedAtDesc(UUID orderId, OrderAssignment.Status status);

    List<OrderAssignment> findByPartnerIdAndStatusOrderByOfferedAtDesc(UUID partnerId, OrderAssignment.Status status);

    /** One round trip: was this partner ever responsible for the order (accepted now or ended)? */
    boolean existsByOrderIdAndPartnerIdAndStatusIn(UUID orderId, UUID partnerId, Collection<OrderAssignment.Status> statuses);
}
