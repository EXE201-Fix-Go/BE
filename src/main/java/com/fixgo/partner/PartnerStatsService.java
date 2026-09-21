package com.fixgo.partner;

import com.fixgo.common.Actor;
import com.fixgo.dispatch.OrderAssignment;
import com.fixgo.dispatch.OrderAssignmentRepository;
import com.fixgo.order.OrderStatus;
import com.fixgo.payment.Payment;
import com.fixgo.payment.PaymentRepository;
import com.fixgo.payment.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Dashboard numbers for a partner: what they finished and earned today, and how customers rate them. */
@Service
public class PartnerStatsService {
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private final OrderAssignmentRepository assignments;
    private final PaymentRepository payments;
    private final ReviewRepository reviews;
    private final Clock clock;

    public PartnerStatsService(OrderAssignmentRepository assignments, PaymentRepository payments,
                               ReviewRepository reviews, Clock clock) {
        this.assignments = assignments;
        this.payments = payments;
        this.reviews = reviews;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PartnerDtos.StatsResponse stats(Actor partner) {
        Instant startOfToday = LocalDate.now(clock.withZone(VN)).atStartOfDay(VN).toInstant();
        long completedToday = assignments.countCompletedSince(partner.userId(), OrderAssignment.Status.ACCEPTED, startOfToday);
        long completedTotal = assignments.countCompletedSince(partner.userId(), OrderAssignment.Status.ACCEPTED, Instant.EPOCH);
        var earned = payments.sumCollectedSince(partner.userId(), Payment.Status.CONFIRMED, startOfToday);
        Object[] rating = reviews.ratingSummary(partner.userId());
        Object[] row = rating != null && rating.length == 1 && rating[0] instanceof Object[] inner ? inner : rating;
        Double avg = row == null || row[0] == null ? null : ((Number) row[0]).doubleValue();
        long count = row == null || row[1] == null ? 0 : ((Number) row[1]).longValue();
        long active = assignments.findByPartnerIdAndStatusOrderByOfferedAtDesc(partner.userId(), OrderAssignment.Status.ACCEPTED)
                .size();
        return new PartnerDtos.StatsResponse(completedToday, earned, completedTotal, avg, count, active);
    }
}
