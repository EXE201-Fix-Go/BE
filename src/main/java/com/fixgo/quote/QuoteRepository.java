package com.fixgo.quote;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository extends JpaRepository<Quote, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quote q where q.id = :id")
    Optional<Quote> lockById(UUID id);

    List<Quote> findByOrderIdOrderByRevisionNoAsc(UUID orderId);

    Optional<Quote> findFirstByOrderIdAndStatus(UUID orderId, Quote.Status status);

    /** RB-42: the amount due is the APPROVED revision with the highest revision_no. */
    Optional<Quote> findFirstByOrderIdAndStatusOrderByRevisionNoDesc(UUID orderId, Quote.Status status);

    Optional<Quote> findFirstByOrderIdOrderByRevisionNoDesc(UUID orderId);
}
