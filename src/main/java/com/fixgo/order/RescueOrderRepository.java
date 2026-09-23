package com.fixgo.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RescueOrderRepository extends JpaRepository<RescueOrder, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RescueOrder o where o.id = :id")
    Optional<RescueOrder> lockById(UUID id);

    List<RescueOrder> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    @Query("select o from RescueOrder o where o.status = :status and o.createdAt < :before")
    List<RescueOrder> findByStatusCreatedBefore(OrderStatus status, Instant before);

    /** Batch-load orders by IDs in a single IN query (avoids N+1 in listOffers / listActiveJobs). */
    @Query("select o from RescueOrder o where o.id in :ids")
    List<RescueOrder> findAllByIdIn(@org.springframework.data.repository.query.Param("ids") List<UUID> ids);

    /**
     * RB-36: first partner to claim wins. The conditional UPDATE is the arbiter; callers check the row count.
     * The version bump keeps JPA optimistic locking consistent for anyone holding the entity.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RescueOrder o set o.status = :next, o.version = o.version + 1 "
            + "where o.id = :id and o.status = :expected")
    int compareAndSetStatus(UUID id, OrderStatus expected, OrderStatus next);
}
