package com.fixgo.order;

import com.fixgo.catalog.ServiceCatalog;
import com.fixgo.catalog.ServiceCatalogCache;
import com.fixgo.common.Actor;
import com.fixgo.common.ApiException;
import com.fixgo.common.PhoneNumbers;
import com.fixgo.config.CallOutFeeConfigRepository;
import com.fixgo.dispatch.DispatchRound;
import com.fixgo.dispatch.DispatchService;
import com.fixgo.dispatch.OrderAssignment;
import com.fixgo.dispatch.OrderAssignmentRepository;
import com.fixgo.partner.Availability;
import com.fixgo.partner.PartnerProfileRepository;
import com.fixgo.payment.PaymentService;
import com.fixgo.quote.Quote;
import com.fixgo.quote.QuoteMapper;
import com.fixgo.quote.QuoteRepository;
import com.fixgo.user.Role;
import com.fixgo.user.UserIdentityRepository;
import com.fixgo.user.UserRepository;
import com.fixgo.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rescue-order lifecycle (BRD §3.1). Every mutation locks the order row and re-checks who the caller is
 * inside the transaction (NT-02); status changes go through {@link OrderStateMachine}.
 */
@Service
public class OrderService {
    private final RescueOrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final OrderAssignmentRepository assignments;
    private final ServiceCatalogCache catalog;
    private final CallOutFeeConfigRepository feeConfigs;
    private final PartnerProfileRepository partners;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final QuoteRepository quotes;
    private final QuoteMapper quoteMapper;
    private final DispatchService dispatch;
    private final PaymentService payments;
    private final OrderStateMachine stateMachine;
    private final Clock clock;

    public OrderService(RescueOrderRepository orders, OrderStatusHistoryRepository history,
                        OrderAssignmentRepository assignments, ServiceCatalogCache catalog,
                        CallOutFeeConfigRepository feeConfigs, PartnerProfileRepository partners, UserRepository users,
                        UserIdentityRepository identities, QuoteRepository quotes, QuoteMapper quoteMapper,
                        DispatchService dispatch, PaymentService payments, OrderStateMachine stateMachine, Clock clock) {
        this.orders = orders;
        this.history = history;
        this.assignments = assignments;
        this.catalog = catalog;
        this.feeConfigs = feeConfigs;
        this.partners = partners;
        this.users = users;
        this.identities = identities;
        this.quotes = quotes;
        this.quoteMapper = quoteMapper;
        this.dispatch = dispatch;
        this.payments = payments;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ customer

    /** RB-27: orders start in PENDING_CONFIRMATION with the fee snapshotted from the active config (RB-24). */
    @Transactional
    public OrderDtos.OrderResponse create(Actor actor, OrderDtos.CreateOrderRequest request) {
        if (!actor.is(Role.CUSTOMER)) throw forbidden();
        var now = clock.instant();
        var service = catalog.activeByCode(request.serviceId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SERVICE", "Unknown service."));
        var fee = feeConfigs.findActiveGlobal(now)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FEE_CONFIG_MISSING",
                        "No active call-out fee configuration."));
        var user = users.findById(actor.userId()).orElseThrow(UserService::notFound);
        String phone = request.contactPhone() != null && !request.contactPhone().isBlank()
                ? PhoneNumbers.toE164(request.contactPhone())
                : identities.findPrimaryUid(actor.userId()).orElseThrow(() ->
                        new ApiException(HttpStatus.BAD_REQUEST, "CONTACT_PHONE_REQUIRED", "A contact phone is required."));
        String name = request.contactName() != null && !request.contactName().isBlank()
                ? request.contactName().strip() : user.getFullName();
        var order = new RescueOrder(OrderCodes.next(now), actor.userId(), service.getId(), name, phone,
                request.lat(), request.lng(), request.addressText().strip(), request.note(),
                request.vehicleDescription(), fee.getFeeAmount(), fee.getId(), now);
        if (request.extraServiceIds() != null && !request.extraServiceIds().isEmpty()) {
            var extras = catalog.activeByCodes(request.extraServiceIds());
            if (extras.size() != request.extraServiceIds().stream().distinct().count()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SERVICE", "Unknown extra service.");
            }
            extras.stream().map(ServiceCatalog::getId).filter(id -> !id.equals(service.getId()))
                    .forEach(order.getExtraServiceIds()::add);
        }
        if (request.photoUrls() != null) order.getPhotoUrls().addAll(request.photoUrls());
        orders.save(order);
        stateMachine.recordCreation(order, actor.userId());
        return toResponse(order);
    }

    /** BR01: the customer confirms the call-out fee, then the order enters dispatch. */
    @Transactional
    public OrderDtos.OrderResponse confirm(Actor actor, UUID orderId) {
        var order = lockOwned(actor, orderId);
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) throw OrderStateMachine.wrongState(order.getStatus(), "confirm");
        order.confirmCallOutFee(clock.instant());
        stateMachine.transition(order, OrderStatus.REQUESTED, actor.userId(), ActorType.CUSTOMER, "Call-out fee confirmed");
        dispatch.startRound(order, 1);
        return toResponse(order);
    }

    /** BR09: who, when, why. Customer, assigned partner or admin; legality of the state is checked by the machine. */
    @Transactional
    public OrderDtos.OrderResponse cancel(Actor actor, UUID orderId, String reason) {
        var order = orders.lockById(orderId).orElseThrow(OrderService::notFound);
        boolean allowed = switch (actor.role()) {
            case CUSTOMER -> order.getCustomerId().equals(actor.userId());
            case PARTNER -> isCurrentPartner(actor, order);
            case ADMIN -> true;
        };
        if (!allowed) throw notFoundOrForbidden(actor, order);
        cancelLocked(order, actor, reason);
        return toResponse(order);
    }

    /** Shared with quote decline (GW-02). Caller holds the order lock. */
    @Transactional
    public void cancelLocked(RescueOrder order, Actor actor, String reason) {
        var now = clock.instant();
        var source = ActorType.of(actor.role());
        stateMachine.transition(order, OrderStatus.CANCELLED, actor.userId(), source, reason);
        order.recordCancellation(actor.userId(), source, reason, now);
        dispatch.closeOpenRounds(order.getId(), DispatchRound.EndReason.CANCELLED);
        var current = dispatch.currentAssignment(order.getId()).orElse(null);
        if (current != null) {
            current.end(now);
            partners.lockById(current.getPartnerId()).ifPresent(p -> p.setAvailability(Availability.ONLINE));
            // Decision (BRD §9, pending → resolved): cancelling after the partner arrived keeps the call-out fee due.
            if (current.getArrivedAt() != null && source == ActorType.CUSTOMER) {
                payments.createPending(order.getId(), null, current.getPartnerId(), order.getCallOutFeeSnapshot(),
                        "order:" + order.getId() + ":call-out-fee");
            }
        }
        quotes.findFirstByOrderIdAndStatus(order.getId(), Quote.Status.SENT).ifPresent(q ->
                q.decline(actor.userId(), "ORDER_CANCELLED", now));
    }

    // ------------------------------------------------------------------ partner

    @Transactional
    public OrderDtos.OrderResponse arrive(Actor actor, UUID orderId) {
        var order = lockAssigned(actor, orderId);
        stateMachine.transition(order, OrderStatus.ARRIVED, actor.userId(), ActorType.PARTNER, null);
        dispatch.currentAssignment(orderId).ifPresent(a -> a.markArrived(clock.instant()));
        return toResponse(order);
    }

    @Transactional
    public OrderDtos.OrderResponse startChecking(Actor actor, UUID orderId) {
        var order = lockAssigned(actor, orderId);
        stateMachine.transition(order, OrderStatus.CHECKING, actor.userId(), ActorType.PARTNER, null);
        return toResponse(order);
    }

    @Transactional
    public OrderDtos.OrderResponse pause(Actor actor, UUID orderId, String note) {
        var order = lockAssigned(actor, orderId);
        stateMachine.transition(order, OrderStatus.PAUSED, actor.userId(), ActorType.PARTNER, note);
        return toResponse(order);
    }

    @Transactional
    public OrderDtos.OrderResponse resume(Actor actor, UUID orderId) {
        var order = lockAssigned(actor, orderId);
        if (order.getStatus() != OrderStatus.PAUSED) throw OrderStateMachine.wrongState(order.getStatus(), "resume");
        stateMachine.transition(order, OrderStatus.IN_PROGRESS, actor.userId(), ActorType.PARTNER, null);
        return toResponse(order);
    }

    /** BR02: nothing completes without an APPROVED quote; the amount due is that quote's total (RB-42). */
    @Transactional
    public OrderDtos.OrderResponse complete(Actor actor, UUID orderId) {
        var order = lockAssigned(actor, orderId);
        var approved = quotes.findFirstByOrderIdAndStatusOrderByRevisionNoDesc(orderId, Quote.Status.APPROVED)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUOTE_NOT_APPROVED",
                        "The customer has not approved a quote."));
        var now = clock.instant();
        stateMachine.transition(order, OrderStatus.COMPLETED, actor.userId(), ActorType.PARTNER, null);
        order.markCompleted(now);
        partners.lockById(actor.userId()).ifPresent(p -> p.setAvailability(Availability.ONLINE));
        payments.createPending(orderId, approved.getId(), actor.userId(), approved.getTotalAmount(),
                "order:" + orderId + ":final");
        // Pilot: the partner collects cash when finishing, so completing IS the payment confirmation (RB-59).
        payments.confirmIfPending(actor, orderId);
        return toResponse(order);
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public OrderDtos.OrderResponse get(Actor actor, UUID orderId) {
        return toResponse(visibleOrder(actor, orderId));
    }

    /** Lightweight poll target: status only (3 queries instead of ~15), so mobile clients can poll cheaply. */
    @Transactional(readOnly = true)
    public OrderDtos.OrderStatusResponse getStatus(Actor actor, UUID orderId) {
        var order = visibleOrder(actor, orderId);
        return new OrderDtos.OrderStatusResponse(order.getId(), order.getOrderCode(), order.getStatus(), order.getVersion());
    }

    private RescueOrder visibleOrder(Actor actor, UUID orderId) {
        var order = orders.findById(orderId).orElseThrow(OrderService::notFound);
        boolean visible = switch (actor.role()) {
            case CUSTOMER -> order.getCustomerId().equals(actor.userId());
            case PARTNER -> assignments.existsByOrderIdAndPartnerIdAndStatusIn(orderId, actor.userId(),
                    List.of(OrderAssignment.Status.ACCEPTED, OrderAssignment.Status.ENDED));
            case ADMIN -> true;
        };
        if (!visible) throw notFound();
        return order;
    }

    @Transactional(readOnly = true)
    public List<OrderDtos.OrderResponse> listMine(Actor actor) {
        if (!actor.is(Role.CUSTOMER)) throw forbidden();
        return orders.findByCustomerIdOrderByCreatedAtDesc(actor.userId()).stream().map(this::toResponse).toList();
    }

    // ------------------------------------------------------------------ helpers

    private RescueOrder lockOwned(Actor actor, UUID orderId) {
        var order = orders.lockById(orderId).orElseThrow(OrderService::notFound);
        if (!actor.is(Role.CUSTOMER) || !order.getCustomerId().equals(actor.userId())) {
            throw notFoundOrForbidden(actor, order);
        }
        return order;
    }

    /** The partner who currently holds the ACCEPTED assignment. */
    public RescueOrder lockAssigned(Actor actor, UUID orderId) {
        var order = orders.lockById(orderId).orElseThrow(OrderService::notFound);
        if (!actor.is(Role.PARTNER) || !isCurrentPartner(actor, order)) throw notFoundOrForbidden(actor, order);
        return order;
    }

    private boolean isCurrentPartner(Actor actor, RescueOrder order) {
        return dispatch.currentAssignment(order.getId()).map(a -> a.getPartnerId().equals(actor.userId())).orElse(false);
    }

    /** Owners of other orders get 404 (no existence leak); wrong roles on a visible order get 403. */
    private ApiException notFoundOrForbidden(Actor actor, RescueOrder order) {
        boolean related = order.getCustomerId().equals(actor.userId()) || isCurrentPartner(actor, order)
                || actor.is(Role.ADMIN);
        return related ? forbidden() : notFound();
    }

    @Transactional(readOnly = true)
    public OrderDtos.OrderResponse toResponse(RescueOrder o) {
        var service = catalog.byId(o.getRequestedServiceId()).orElse(null);
        Map<UUID, ServiceCatalog> extras = catalog.byIds(o.getExtraServiceIds());
        var partner = assignments.findPartnerSummary(o.getId(), OrderAssignment.Status.ACCEPTED).stream().findFirst()
                .map(r -> new OrderDtos.PartnerSummary((UUID) r[0], (String) r[1], (String) r[2], (Double) r[3],
                        (Double) r[4], (java.time.Instant) r[5], (java.time.Instant) r[6]))
                .orElse(null);
        var quote = quotes.findFirstByOrderIdOrderByRevisionNoDesc(o.getId()).map(quoteMapper::toResponse).orElse(null);
        var payment = payments.latest(o.getId()).map(p -> new OrderDtos.PaymentSummary(p.getId(), p.getAmount(),
                p.getStatus().name(), p.getMethod().name(), p.getConfirmedAt())).orElse(null);
        var entries = history.findByOrderIdOrderByChangedAtAsc(o.getId()).stream().map(h ->
                new OrderDtos.HistoryEntry(h.getFromStatus(), h.getToStatus(), h.getActorType(), h.getNote(),
                        h.getChangedAt())).toList();
        return new OrderDtos.OrderResponse(o.getId(), o.getOrderCode(), o.getStatus(),
                service == null ? null : service.getCode(), service == null ? null : service.getName(),
                o.getExtraServiceIds().stream().map(id -> extras.get(id)).filter(s -> s != null)
                        .map(ServiceCatalog::getCode).toList(),
                o.getPickupAddressText(), o.getPickupNote(), List.copyOf(o.getPhotoUrls()),
                o.getContactName(), o.getContactPhone(), o.getPickupLat(),
                o.getPickupLng(), o.getCallOutFeeSnapshot(), o.getCreatedAt(), o.getConfirmedAt(), o.getCompletedAt(),
                partner, quote, payment, o.getCancellationSource(), o.getCancellationReason(), o.getCancelledAt(),
                entries);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order does not exist.");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.");
    }
}
