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

    /**
     * Current partner card in ONE round trip: assignment + user + profile + primary phone.
     * Columns: partnerId, fullName, phone, lat, lng, acceptedAt, arrivedAt.
     */
    @Query("""
            select a.partnerId, u.fullName, i.providerUid, p.currentLat, p.currentLng, a.acceptedAt, a.arrivedAt
            from OrderAssignment a
            left join User u on u.id = a.partnerId
            left join PartnerProfile p on p.userId = a.partnerId
            left join UserIdentity i on i.user.id = a.partnerId and i.primary = true
            where a.orderId = :orderId and a.status = :status
            order by a.acceptedAt desc
            """)
    List<Object[]> findPartnerSummary(UUID orderId, OrderAssignment.Status status);

    /** Orders this partner completed since a point in time (today's tally on the dashboard). */
    @Query("""
            select count(a) from OrderAssignment a, RescueOrder o
            where a.partnerId = :partnerId and a.status = :status and o.id = a.orderId
              and o.status = com.fixgo.order.OrderStatus.COMPLETED and o.completedAt >= :since
            """)
    long countCompletedSince(UUID partnerId, OrderAssignment.Status status, java.time.Instant since);

    /** One round trip: was this partner ever responsible for the order (accepted now or ended)? */
    boolean existsByOrderIdAndPartnerIdAndStatusIn(UUID orderId, UUID partnerId, Collection<OrderAssignment.Status> statuses);
}
