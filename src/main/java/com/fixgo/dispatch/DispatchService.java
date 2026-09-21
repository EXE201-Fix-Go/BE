package com.fixgo.dispatch;

import com.fixgo.catalog.ServiceCatalogRepository;
import com.fixgo.common.Actor;
import com.fixgo.common.ApiException;
import com.fixgo.config.DispatchPolicy;
import com.fixgo.config.DispatchPolicyRepository;
import com.fixgo.config.DispatchProperties;
import com.fixgo.order.*;
import com.fixgo.partner.Availability;
import com.fixgo.partner.PartnerProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Broadcast dispatch (BRD §3.5, BR07/BR08, RB-34..36). */
@Service
public class DispatchService {
    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private final DispatchRoundRepository rounds;
    private final OrderAssignmentRepository assignments;
    private final DispatchPolicyRepository policies;
    private final PartnerProfileRepository partners;
    private final RescueOrderRepository orders;
    private final ServiceCatalogRepository catalog;
    private final OrderStateMachine stateMachine;
    private final DispatchProperties properties;
    private final Clock clock;

    public DispatchService(DispatchRoundRepository rounds, OrderAssignmentRepository assignments,
                           DispatchPolicyRepository policies, PartnerProfileRepository partners,
                           RescueOrderRepository orders, ServiceCatalogRepository catalog,
                           OrderStateMachine stateMachine, DispatchProperties properties, Clock clock) {
        this.rounds = rounds;
        this.assignments = assignments;
        this.policies = policies;
        this.partners = partners;
        this.orders = orders;
        this.catalog = catalog;
        this.stateMachine = stateMachine;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens round N for an order in REQUESTED (caller holds the order lock). With zero candidates the round
     * closes immediately and the next radius is tried; after the final round the order becomes NO_PARTNER_FOUND.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startRound(RescueOrder order, int roundNo) {
        var now = clock.instant();
        var policy = policies.findActiveGlobalRound(roundNo, now).orElse(null);
        if (policy == null) {
            // No policy for this round means the previous one was effectively final (RB-26 misconfiguration guard).
            log.warn("No dispatch policy for round {} — closing order {} as NO_PARTNER_FOUND", roundNo, order.getOrderCode());
            stateMachine.transition(order, OrderStatus.NO_PARTNER_FOUND, null, ActorType.SYSTEM, "No dispatch policy");
            return;
        }
        List<UUID> candidates = partners.findCandidates(order.getPickupLat(), order.getPickupLng(), policy.getRadiusM(),
                order.getRequestedServiceId(), policy.isIncludeLowerPriority());
        var expiresAt = now.plus(Duration.ofSeconds(policy.getTimeoutSeconds()));
        var round = rounds.save(new DispatchRound(order.getId(), policy.getId(), roundNo, policy.getRadiusM(), now,
                expiresAt, candidates.size(), candidates.isEmpty() ? noCandidateReason(order, policy) : null));
        if (candidates.isEmpty()) {
            round.end(DispatchRound.EndReason.NO_CANDIDATE, now);
            if (policy.isFinalRound()) {
                stateMachine.transition(order, OrderStatus.NO_PARTNER_FOUND, null, ActorType.SYSTEM,
                        "Round " + roundNo + ": no candidates");     // RB-34
            } else {
                startRound(order, roundNo + 1);                        // BR08: widen immediately, nobody to wait for
            }
            return;
        }
        for (UUID partnerId : candidates) {
            assignments.save(new OrderAssignment(order.getId(), round.getId(), partnerId,
                    OrderAssignment.Source.BROADCAST, null, now, expiresAt));
        }
        // dispatch_notifications (PUSH/ZALO/SMS) are recorded here once a provider is wired up.
    }

    private DispatchRound.NoCandidateReason noCandidateReason(RescueOrder order, DispatchPolicy policy) {
        if (partners.countOnlineEligible() == 0) return DispatchRound.NoCandidateReason.NO_PARTNER_ONLINE;
        if (partners.countOnlineWithinRadius(order.getPickupLat(), order.getPickupLng(), policy.getRadiusM()) == 0) {
            return DispatchRound.NoCandidateReason.OUT_OF_RADIUS;
        }
        return DispatchRound.NoCandidateReason.NO_SERVICE_MATCH;
    }

    /** Ends every open round/offer for an order (cancellation, admin assignment). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeOpenRounds(UUID orderId, DispatchRound.EndReason reason) {
        var now = clock.instant();
        for (var round : rounds.findByOrderIdAndEndedAtIsNull(orderId)) {
            round.end(reason, now);
            assignments.findByDispatchRoundId(round.getId()).forEach(a -> a.expire(now));
        }
    }

    @Transactional(readOnly = true)
    public Optional<OrderAssignment> currentAssignment(UUID orderId) {
        return assignments.findFirstByOrderIdAndStatusOrderByAcceptedAtDesc(orderId, OrderAssignment.Status.ACCEPTED);
    }

    @Transactional(readOnly = true)
    public List<DispatchDtos.OfferResponse> listOffers(Actor partner) {
        var now = clock.instant();
        return assignments.findByPartnerIdAndStatusOrderByOfferedAtDesc(partner.userId(), OrderAssignment.Status.OFFERED)
                .stream().filter(a -> a.isOpenOffer(now))
                .map(this::toOffer).filter(o -> o.orderStatus() == OrderStatus.REQUESTED).toList();
    }

    @Transactional(readOnly = true)
    public List<DispatchDtos.OfferResponse> listActiveJobs(Actor partner) {
        return assignments.findByPartnerIdAndStatusOrderByOfferedAtDesc(partner.userId(), OrderAssignment.Status.ACCEPTED)
                .stream().map(this::toOffer).filter(o -> !o.orderStatus().isTerminal()).toList();
    }

    /** RB-36: the conditional UPDATE on rescue_orders decides who wins; everything else follows from it. */
    @Transactional
    public DispatchDtos.OfferResponse accept(Actor partner, UUID assignmentId) {
        var now = clock.instant();
        var assignment = assignments.lockById(assignmentId).orElseThrow(DispatchService::offerNotFound);
        if (!assignment.getPartnerId().equals(partner.userId())) throw offerNotFound();
        if (!assignment.isOpenOffer(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_CLOSED", "This offer has expired or was withdrawn.");
        }
        var profile = partners.lockById(partner.userId()).orElseThrow(DispatchService::offerNotFound);
        if (!profile.canTakeOrders()) {                                  // RB-16 re-check inside the transaction
            throw new ApiException(HttpStatus.FORBIDDEN, "PARTNER_NOT_ELIGIBLE", "Your profile cannot take orders.");
        }
        int claimed = orders.compareAndSetStatus(assignment.getOrderId(), OrderStatus.REQUESTED, OrderStatus.ASSIGNED);
        if (claimed == 0) {
            assignment.expire(now);
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_ALREADY_TAKEN", "Another partner accepted first.");
        }
        assignment.accept(now);
        profile.setAvailability(Availability.BUSY);
        if (assignment.getDispatchRoundId() != null) {
            rounds.lockById(assignment.getDispatchRoundId()).ifPresent(r -> r.end(DispatchRound.EndReason.CLAIMED, now));
            assignments.findByDispatchRoundId(assignment.getDispatchRoundId()).stream()
                    .filter(a -> !a.getId().equals(assignment.getId())).forEach(a -> a.expire(now));
        }
        var order = orders.findById(assignment.getOrderId()).orElseThrow(DispatchService::offerNotFound);
        stateMachine.recordExternal(order.getId(), OrderStatus.REQUESTED, OrderStatus.ASSIGNED, partner.userId(),
                ActorType.PARTNER, "Accepted by broadcast");
        return toOffer(assignment, order);
    }

    @Transactional
    public void decline(Actor partner, UUID assignmentId, String reason) {
        var assignment = assignments.lockById(assignmentId).orElseThrow(DispatchService::offerNotFound);
        if (!assignment.getPartnerId().equals(partner.userId())) throw offerNotFound();
        if (assignment.getStatus() != OrderAssignment.Status.OFFERED) {
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_CLOSED", "This offer is no longer open.");
        }
        assignment.decline(reason, clock.instant());
    }

    /**
     * Housekeeping: rounds past their deadline widen to the next radius or end as NO_PARTNER_FOUND;
     * orders never confirmed by the customer expire (RB-27 / §7.1).
     */
    @Transactional
    public void tick() {
        var now = clock.instant();
        for (UUID roundId : rounds.findExpiredOpenIds(now)) {
            var round = rounds.lockById(roundId).orElse(null);
            if (round == null || !round.isOpen()) continue;
            var order = orders.lockById(round.getOrderId()).orElse(null);
            round.end(DispatchRound.EndReason.TIMEOUT, now);
            assignments.findByDispatchRoundId(round.getId()).forEach(a -> a.expire(now));
            if (order == null || order.getStatus() != OrderStatus.REQUESTED) continue;
            var policy = policies.findById(round.getPolicyId()).orElse(null);
            if (policy == null || policy.isFinalRound()) {
                stateMachine.transition(order, OrderStatus.NO_PARTNER_FOUND, null, ActorType.SYSTEM,
                        "Round " + round.getRoundNo() + " timed out");
            } else {
                startRound(order, round.getRoundNo() + 1);
            }
        }
        var cutoff = now.minus(properties.pendingConfirmationTtl());
        for (var stale : orders.findByStatusCreatedBefore(OrderStatus.PENDING_CONFIRMATION, cutoff)) {
            var order = orders.lockById(stale.getId()).orElse(null);
            if (order != null && order.getStatus() == OrderStatus.PENDING_CONFIRMATION) {
                stateMachine.transition(order, OrderStatus.EXPIRED, null, ActorType.SYSTEM, "Call-out fee never confirmed");
            }
        }
    }

    private DispatchDtos.OfferResponse toOffer(OrderAssignment a) {
        return toOffer(a, orders.findById(a.getOrderId()).orElseThrow(DispatchService::offerNotFound));
    }

    private DispatchDtos.OfferResponse toOffer(OrderAssignment a, RescueOrder o) {
        var service = catalog.findById(o.getRequestedServiceId()).orElse(null);
        int roundNo = a.getDispatchRoundId() == null ? 0
                : rounds.findById(a.getDispatchRoundId()).map(DispatchRound::getRoundNo).orElse(0);
        return new DispatchDtos.OfferResponse(a.getId(), o.getId(), o.getOrderCode(), o.getStatus(),
                service == null ? null : service.getCode(), service == null ? null : service.getName(),
                o.getPickupAddressText(), o.getPickupNote(), o.getPickupLat(), o.getPickupLng(),
                o.getCallOutFeeSnapshot(), o.getContactName(), a.getOfferedAt(), a.getExpiresAt(), roundNo);
    }

    private static ApiException offerNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "Offer does not exist.");
    }
}
