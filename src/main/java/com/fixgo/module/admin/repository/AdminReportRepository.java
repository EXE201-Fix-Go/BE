package com.fixgo.module.admin.repository;

import com.fixgo.module.dispatch.entity.OrderAssignment;
import com.fixgo.module.order.entity.RescueOrder;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.payment.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only order/revenue queries for the admin dashboard. Kept in its own repository so the existing order
 * repositories stay untouched.
 */
public interface AdminReportRepository extends Repository<RescueOrder, UUID> {

    /** Columns: status, count. */
    @Query("select o.status, count(o) from RescueOrder o group by o.status")
    List<Object[]> countByStatus();

    @Query("select count(o) from RescueOrder o where o.createdAt >= :since")
    long countCreatedSince(@Param("since") Instant since);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.status = :status and p.confirmedAt >= :since")
    BigDecimal sumPaymentsConfirmedSince(@Param("status") Payment.Status status, @Param("since") Instant since);

    /**
     * RB-23: the platform commission rate is configuration, not a constant. The table has no JPA entity, so this
     * reads it natively (the schema comes from the connection's search_path, as for the other native queries).
     */
    @Query(value = """
            select commission_rate from commission_configs
            where scope_type = 'GLOBAL' and effective_from <= now() and (effective_to is null or effective_to > now())
            limit 1
            """, nativeQuery = true)
    Optional<BigDecimal> activeGlobalCommissionRate();

    Page<RescueOrder> findAllByOrderByCreatedAtDescIdAsc(Pageable pageable);

    Page<RescueOrder> findByStatusOrderByCreatedAtDescIdAsc(OrderStatus status, Pageable pageable);

    /** Columns: orderId, partner full name — oldest first, so a later reassignment overrides an earlier one. */
    @Query("""
            select a.orderId, u.fullName
            from OrderAssignment a join User u on u.id = a.partnerId
            where a.orderId in :orderIds and a.status in :statuses
            order by a.acceptedAt asc
            """)
    List<Object[]> partnerNamesForOrders(@Param("orderIds") Collection<UUID> orderIds,
                                         @Param("statuses") Collection<OrderAssignment.Status> statuses);

    /** Columns: orderId, total amount with the given payment status. */
    @Query("select p.orderId, sum(p.amount) from Payment p where p.orderId in :orderIds and p.status = :status "
            + "group by p.orderId")
    List<Object[]> paidAmountsForOrders(@Param("orderIds") Collection<UUID> orderIds,
                                        @Param("status") Payment.Status status);
}
