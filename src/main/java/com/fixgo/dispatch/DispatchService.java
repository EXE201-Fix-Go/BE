package com.fixgo.dispatch;

import com.fixgo.catalog.ServiceCatalogCache;
import com.fixgo.common.Actor;
import com.fixgo.common.ApiException;
import com.fixgo.common.GeoDistance;
import com.fixgo.config.DispatchPolicy;
import com.fixgo.config.DispatchPolicyRepository;
import com.fixgo.config.DispatchProperties;
import com.fixgo.config.TravelFeeConfigRepository;
import com.fixgo.order.*;
import com.fixgo.partner.Availability;
import com.fixgo.partner.PartnerProfile;
import com.fixgo.partner.PartnerProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Broadcast dispatch (BRD §3.5, BR07/BR08, RB-34..36). */
@Service
public class DispatchService {
    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private final DispatchRoundRepository rounds;
    private final OrderAssignmentRepository assignments;
    private final DispatchPolicyRepository policies;
    private final PartnerProfileRepository partners;
    private final RescueOrderRepository orders;
    private final ServiceCatalogCache catalog;
    private final OrderStateMachine stateMachine;
    private final DispatchProperties properties;
    private final TravelFeeConfigRepository travelFees;
    private final Clock clock;

    public DispatchService(DispatchRoundRepository rounds, OrderAssignmentRepository assignments,
                           DispatchPolicyRepository policies, PartnerProfileRepository partners,
                           RescueOrderRepository orders, ServiceCatalogCache catalog,
                           OrderStateMachine stateMachine, DispatchProperties properties,
                           TravelFeeConfigRepository travelFees, Clock clock) {
        this.rounds = rounds;
        this.assignments = assignments;
        this.policies = policies;
        this.partners = partners;
        this.orders = orders;
        this.catalog = catalog;
        this.stateMachine = stateMachine;
        this.properties = properties;
        this.travelFees = travelFees;
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
        List<OrderAssignment> newAssignments = candidates.stream()
                .map(partnerId -> new OrderAssignment(order.getId(), round.getId(), partnerId,
                        OrderAssignment.Source.BROADCAST, null, now, expiresAt))
                .toList();
        assignments.saveAll(newAssignments);
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
        List<OrderAssignment> list = assignments
                .findByPartnerIdAndStatusOrderByOfferedAtDesc(partner.userId(), OrderAssignment.Status.OFFERED)
                .stream().filter(a -> a.isOpenOffer(now)).toList();
        // Batch-load orders and rounds in 2 IN queries instead of N×2 individual findById calls.
        Map<UUID, com.fixgo.order.RescueOrder> orderMap = batchLoadOrders(list);
        Map<UUID, DispatchRound> roundMap = batchLoadRounds(list);
        return list.stream()
                .map(a -> toOfferBatch(a, orderMap, roundMap))
                .filter(o -> o != null && o.orderStatus() == OrderStatus.REQUESTED)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DispatchDtos.OfferResponse> listActiveJobs(Actor partner) {
        List<OrderAssignment> list = assignments
                .findByPartnerIdAndStatusOrderByOfferedAtDesc(partner.userId(), OrderAssignment.Status.ACCEPTED);
        // Batch-load orders and rounds in 2 IN queries instead of N×2 individual findById calls.
        Map<UUID, com.fixgo.order.RescueOrder> orderMap = batchLoadOrders(list);
        Map<UUID, DispatchRound> roundMap = batchLoadRounds(list);
        return list.stream()
                .map(a -> toOfferBatch(a, orderMap, roundMap))
                .filter(o -> o != null && !o.orderStatus().isTerminal())
                .toList();
    }

    public DispatchDtos.OfferResponse accept(Actor partner, UUID assignmentId) {
        return accept(partner, assignmentId, null);
    }

    /** RB-36: the conditional UPDATE on rescue_orders decides who wins; everything else follows from it. */
    @Transactional
    public DispatchDtos.OfferResponse accept(Actor partner, UUID assignmentId, DispatchDtos.AcceptRequest req) {
        var now = clock.instant();
        var assignment = assignments.lockById(assignmentId).orElseThrow(DispatchService::offerNotFound);
        if (!assignment.getPartnerId().equals(partner.userId())) throw offerNotFound();
        if (!assignment.isOpenOffer(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_CLOSED", "This offer has expired or was withdrawn.");
        }
        var profile = partners.lockById(partner.userId()).orElseThrow(DispatchService::offerNotFound);
        // Mốc tính phí di chuyển = vị trí GPS thợ ngay lúc bấm nhận (nếu app gửi lên), thay cho vị trí lúc online.
        if (req != null && req.lat() != null && req.lng() != null) {
            profile.updatePresence(Availability.ONLINE, req.lat(), req.lng(), now);
        }
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
        // Load order AFTER compareAndSetStatus succeeds: status is now ASSIGNED in DB, lockById reads it fresh.
        // This replaces the old findById() that came after, eliminating one separate SELECT round-trip.
        var order = orders.lockById(assignment.getOrderId()).orElseThrow(DispatchService::offerNotFound);
        snapshotTravel(order, profile, now);
        stateMachine.recordExternal(order.getId(), OrderStatus.REQUESTED, OrderStatus.ASSIGNED, partner.userId(),
                ActorType.PARTNER, "Accepted by broadcast");
        return toOffer(assignment, order);
    }

    /** Snapshots straight-line km from the partner's location to the pickup and the resulting travel fee (RB-23). */
    private void snapshotTravel(RescueOrder order, PartnerProfile profile, java.time.Instant now) {
        if (profile.getCurrentLat() == null || profile.getCurrentLng() == null) return;
        var cfg = travelFees.findActiveGlobal(now).orElse(null);
        if (cfg == null) return;
        double km = GeoDistance.haversineKm(profile.getCurrentLat(), profile.getCurrentLng(),
                order.getPickupLat(), order.getPickupLng());
        BigDecimal distanceKm = BigDecimal.valueOf(km).setScale(1, RoundingMode.HALF_UP);
        BigDecimal billableKm = distanceKm.subtract(cfg.getFreeKm()).max(BigDecimal.ZERO)
                .setScale(0, RoundingMode.CEILING);
        BigDecimal fee = billableKm.multiply(cfg.getPerKmAmount()).setScale(0, RoundingMode.HALF_UP);
        order.recordTravel(distanceKm, fee, cfg.getId());
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

    // -------------------------------------------------------------------------
    // Batch helpers — used by listOffers() and listActiveJobs() to avoid N+1
    // -------------------------------------------------------------------------

    /** Collect all distinct orderIds from a list of assignments, then load them in one IN query. */
    private Map<UUID, com.fixgo.order.RescueOrder> batchLoadOrders(List<OrderAssignment> list) {
        List<UUID> orderIds = list.stream().map(OrderAssignment::getOrderId).distinct().toList();
        if (orderIds.isEmpty()) return Map.of();
        return orders.findAllByIdIn(orderIds).stream()
                .collect(Collectors.toMap(com.fixgo.order.RescueOrder::getId, o -> o));
    }

    /** Collect all distinct non-null dispatchRoundIds, then load them in one IN query. */
    private Map<UUID, DispatchRound> batchLoadRounds(List<OrderAssignment> list) {
        List<UUID> roundIds = list.stream()
                .map(OrderAssignment::getDispatchRoundId)
                .filter(id -> id != null)
                .distinct().toList();
        if (roundIds.isEmpty()) return Map.of();
        return rounds.findAllByIdIn(roundIds).stream()
                .collect(Collectors.toMap(DispatchRound::getId, r -> r));
    }

    /**
     * Map a single assignment to OfferResponse using pre-loaded maps.
     * Returns null if the order is missing (data inconsistency — caller should filter).
     */
    private DispatchDtos.OfferResponse toOfferBatch(
            OrderAssignment a,
            Map<UUID, com.fixgo.order.RescueOrder> orderMap,
            Map<UUID, DispatchRound> roundMap) {
        var o = orderMap.get(a.getOrderId());
        if (o == null) return null;
        return toOffer(a, o, roundMap);
    }

    // -------------------------------------------------------------------------
    // Single-item helpers — kept for accept() which already has the order loaded
    // -------------------------------------------------------------------------

    /** Used in accept(): order is already loaded from the write-path, no extra query needed. */
    private DispatchDtos.OfferResponse toOffer(OrderAssignment a, RescueOrder o) {
        return toOffer(a, o, Map.of());
    }

    private DispatchDtos.OfferResponse toOffer(OrderAssignment a, RescueOrder o, Map<UUID, DispatchRound> roundMap) {
        var service = catalog.byId(o.getRequestedServiceId()).orElse(null);
        int roundNo = 0;
        if (a.getDispatchRoundId() != null) {
            var round = roundMap.get(a.getDispatchRoundId());
            if (round == null) {
                // Fallback: single query only in single-item context (accept() path)
                round = rounds.findById(a.getDispatchRoundId()).orElse(null);
            }
            roundNo = round != null ? round.getRoundNo() : 0;
        }
        return new DispatchDtos.OfferResponse(a.getId(), o.getId(), o.getOrderCode(), o.getStatus(),
                service == null ? null : service.getCode(), service == null ? null : service.getName(),
                o.getPickupAddressText(), o.getPickupNote(), o.getPickupLat(), o.getPickupLng(),
                o.getCallOutFeeSnapshot(), o.getContactName(), a.getOfferedAt(), a.getExpiresAt(), roundNo,
                List.copyOf(o.getPhotoUrls()));
    }

    private static ApiException offerNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "Offer does not exist.");
    }
}
