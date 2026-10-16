package com.fixgo.module.demobot.service;

import com.fixgo.module.catalog.entity.ServiceCatalog;
import com.fixgo.module.catalog.repository.ServiceCatalogRepository;
import com.fixgo.module.catalog.service.ServiceCatalogCache;
import com.fixgo.module.dispatch.entity.DispatchRound;
import com.fixgo.module.dispatch.entity.OrderAssignment;
import com.fixgo.module.dispatch.repository.OrderAssignmentRepository;
import com.fixgo.module.dispatch.service.DispatchService;
import com.fixgo.module.iam.entity.User;
import com.fixgo.module.iam.entity.UserIdentity;
import com.fixgo.module.iam.enums.AccountStatus;
import com.fixgo.module.iam.enums.IdentityProvider;
import com.fixgo.module.iam.enums.Role;
import com.fixgo.module.iam.repository.UserIdentityRepository;
import com.fixgo.module.iam.repository.UserRepository;
import com.fixgo.module.order.dto.QuoteDtos;
import com.fixgo.module.order.entity.QuoteItem;
import com.fixgo.module.order.entity.RescueOrder;
import com.fixgo.module.order.enums.ActorType;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.order.repository.RescueOrderRepository;
import com.fixgo.module.order.service.OrderService;
import com.fixgo.module.order.service.OrderStateMachine;
import com.fixgo.module.order.service.QuoteService;
import com.fixgo.module.partner.entity.PartnerProfile;
import com.fixgo.module.partner.entity.PartnerServiceOffer;
import com.fixgo.module.partner.enums.Availability;
import com.fixgo.module.partner.enums.PartnerType;
import com.fixgo.module.partner.enums.VerificationStatus;
import com.fixgo.module.partner.repository.PartnerProfileRepository;
import com.fixgo.module.partner.repository.PartnerServiceOfferRepository;
import com.fixgo.module.pricing.repository.TravelFeeConfigRepository;
import com.fixgo.module.pricing.service.TravelFeeCalculator;
import com.fixgo.shared.config.DemoBotProperties;
import com.fixgo.shared.util.Actor;
import com.fixgo.shared.util.GeoDistance;
import com.fixgo.shared.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Automated Demo Bot Mechanic ("Thợ Bot Tự Động") orchestrator.
 *
 * <p>Uses real {@link RescueOrder}, {@link OrderAssignment}, and {@link com.fixgo.module.order.entity.Quote}
 * tables with a dedicated seeded partner account ({@code 0900000000} / "Nguyễn Văn Hùng (Thợ Hỗ Trợ Test)")
 * so single-device testers anywhere can experience the full rescue flow when no real mechanic accepts within
 * {@code fixgo.demo-bot.delay-seconds}.
 */
@Service
public class DemoBotService implements ApplicationRunner, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DemoBotService.class);

    public static final String BOT_PHONE_RAW = "0900000000";
    public static final String BOT_FULL_NAME = "Nguyễn Văn Hùng (Thợ Ảo)";

    private final AtomicBoolean enabled;
    private final AtomicInteger delaySeconds;
    private volatile UUID botUserId;

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PartnerProfileRepository partners;
    private final PartnerServiceOfferRepository partnerServices;
    private final ServiceCatalogRepository serviceCatalogRepo;
    private final ServiceCatalogCache catalogCache;
    private final RescueOrderRepository orders;
    private final OrderAssignmentRepository assignments;
    private final TravelFeeConfigRepository travelFees;
    private final OrderStateMachine stateMachine;
    private final DispatchService dispatchService;
    private final OrderService orderService;
    private final QuoteService quoteService;
    private final TransactionTemplate tx;
    private final Clock clock;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "demo-bot-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<UUID, List<ScheduledFuture<?>>> activeTimers = new ConcurrentHashMap<>();

    public DemoBotService(
            DemoBotProperties properties,
            UserRepository users,
            UserIdentityRepository identities,
            PartnerProfileRepository partners,
            PartnerServiceOfferRepository partnerServices,
            ServiceCatalogRepository serviceCatalogRepo,
            ServiceCatalogCache catalogCache,
            RescueOrderRepository orders,
            OrderAssignmentRepository assignments,
            TravelFeeConfigRepository travelFees,
            OrderStateMachine stateMachine,
            @Lazy DispatchService dispatchService,
            @Lazy OrderService orderService,
            @Lazy QuoteService quoteService,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.enabled = new AtomicBoolean(properties.isEnabledByDefault());
        this.delaySeconds = new AtomicInteger(properties.defaultDelaySeconds());
        this.users = users;
        this.identities = identities;
        this.partners = partners;
        this.partnerServices = partnerServices;
        this.serviceCatalogRepo = serviceCatalogRepo;
        this.catalogCache = catalogCache;
        this.orders = orders;
        this.assignments = assignments;
        this.travelFees = travelFees;
        this.stateMachine = stateMachine;
        this.dispatchService = dispatchService;
        this.orderService = orderService;
        this.quoteService = quoteService;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            UUID id = ensureBotAccount();
            log.info("demo-bot.initialized botUserId={} phone={} enabled={} delaySeconds={}s",
                    id, PhoneNumbers.toE164(BOT_PHONE_RAW), enabled.get(), delaySeconds.get());
        } catch (Exception ex) {
            log.warn("demo-bot.init-warning could not seed bot account on startup: {}", ex.getMessage());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ Kill-switch & Status API

    public boolean isEnabled() {
        return enabled.get();
    }

    public int getDelaySeconds() {
        return delaySeconds.get();
    }

    public UUID getBotUserId() {
        return botUserId;
    }

    public BotStatusResponse setEnabled(boolean newState, Integer customDelaySeconds) {
        boolean prev = enabled.getAndSet(newState);
        if (customDelaySeconds != null && customDelaySeconds >= 3 && customDelaySeconds <= 300) {
            delaySeconds.set(customDelaySeconds);
        }
        log.info("demo-bot.toggle previous={} current={} delaySeconds={}s", prev, newState, delaySeconds.get());
        return getStatus();
    }

    public BotStatusResponse toggle() {
        boolean next = !enabled.get();
        enabled.set(next);
        log.info("demo-bot.toggle current={} delaySeconds={}s", next, delaySeconds.get());
        return getStatus();
    }

    public BotStatusResponse getStatus() {
        return new BotStatusResponse(
                enabled.get(),
                delaySeconds.get(),
                botUserId,
                BOT_FULL_NAME,
                BOT_PHONE_RAW
        );
    }

    public record BotStatusResponse(
            boolean enabled,
            int delaySeconds,
            UUID botUserId,
            String botName,
            String botPhone
    ) { }

    // ------------------------------------------------------------------ Seeding / Account Ensure

    public UUID ensureBotAccount() {
        UUID cached = this.botUserId;
        if (cached != null && users.existsById(cached)) {
            return cached;
        }
        return tx.execute(status -> {
            String e164Phone = PhoneNumbers.toE164(BOT_PHONE_RAW);
            var now = clock.instant();

            User botUser;
            var existingIdentity = identities.findByProviderAndProviderUid(IdentityProvider.PHONE, e164Phone).orElse(null);
            if (existingIdentity != null) {
                botUser = existingIdentity.getUser();
                if (botUser.getRole() != Role.PARTNER) {
                    botUser.changeRole(Role.PARTNER);
                }
                if (botUser.getStatus() != AccountStatus.ACTIVE) {
                    botUser.changeStatus(AccountStatus.ACTIVE);
                }
                if (!BOT_FULL_NAME.equals(botUser.getFullName())) {
                    botUser.rename(BOT_FULL_NAME);
                }
                existingIdentity.markVerified(now);
            } else {
                botUser = users.save(new User(Role.PARTNER, BOT_FULL_NAME, now));
                identities.save(new UserIdentity(botUser, IdentityProvider.PHONE, e164Phone, true, now, now));
            }

            UUID uid = botUser.getId();
            var profile = partners.findById(uid).orElse(null);
            if (profile == null) {
                profile = new PartnerProfile(uid, PartnerType.INDIVIDUAL, null, null);
                profile.verify(VerificationStatus.APPROVED, uid, now);
                // Keep OFFLINE by default so real mechanics always get 100% priority in 0s broadcast
                profile.setAvailability(Availability.OFFLINE);
                partners.save(profile);
            } else {
                if (profile.getVerificationStatus() != VerificationStatus.APPROVED) {
                    profile.verify(VerificationStatus.APPROVED, uid, now);
                }
            }

            // Ensure all catalogue services are registered for the Bot Mechanic
            Set<UUID> offeredIds = partnerServices.findByPartnerId(uid).stream()
                    .map(PartnerServiceOffer::getServiceId)
                    .collect(Collectors.toSet());
            for (ServiceCatalog svc : serviceCatalogRepo.findAll()) {
                if (!offeredIds.contains(svc.getId())) {
                    partnerServices.save(new PartnerServiceOffer(uid, svc.getId()));
                }
            }

            this.botUserId = uid;
            return uid;
        });
    }

    // ------------------------------------------------------------------ Order Lifecycle Hooks

    /**
     * Triggered when the customer confirms an order (0s).
     * Schedules the Demo Bot to step in after {@code delaySeconds} (default 20s) if no real mechanic accepts.
     */
    public void onOrderConfirmed(UUID orderId) {
        if (!enabled.get()) {
            log.debug("demo-bot.skip orderId={} (bot is disabled)", orderId);
            return;
        }
        int delay = delaySeconds.get();
        Runnable startWorkflow = () -> {
            log.info("demo-bot.scheduled orderId={} acceptIn={}s", orderId, delay);
            scheduleForOrder(orderId, delay, () -> stepAcceptOrder(orderId));
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    startWorkflow.run();
                }
            });
        } else {
            startWorkflow.run();
        }
    }

    /**
     * Triggered when the customer approves a quote.
     * If the Demo Bot is the assigned partner on this order, schedules completion 15 seconds later.
     */
    public void onQuoteApproved(UUID orderId) {
        UUID botId = this.botUserId;
        if (botId == null || !isAssignedToBot(orderId, botId)) {
            return;
        }
        Runnable scheduleCompletion = () -> {
            log.info("demo-bot.quote-approved orderId={} completingIn=15s", orderId);
            scheduleForOrder(orderId, 15, () -> stepCompleteOrder(orderId));
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    scheduleCompletion.run();
                }
            });
        } else {
            scheduleCompletion.run();
        }
    }

    /**
     * Triggered when an order is cancelled so any pending scheduled steps are immediately aborted.
     */
    public void onOrderCancelled(UUID orderId) {
        cancelScheduledTasks(orderId);
        UUID botId = this.botUserId;
        if (botId != null) {
            try {
                tx.executeWithoutResult(status ->
                        partners.lockById(botId).ifPresent(p -> p.setAvailability(Availability.OFFLINE)));
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ Time-based State Machine Steps

    /**
     * Step 2 (+20s default): Bot accepts the order if still REQUESTED and no real mechanic took it.
     * Sets initial Bot position ~800m–1km away: (lat_khach + 0.006, lng_khach + 0.006).
     */
    private void stepAcceptOrder(UUID orderId) {
        if (!enabled.get()) {
            log.info("demo-bot.aborted-accept orderId={} (bot was turned off)", orderId);
            return;
        }
        try {
            UUID botId = ensureBotAccount();
            Boolean accepted = tx.execute(status -> {
                var order = orders.lockById(orderId).orElse(null);
                if (order == null || order.getStatus() != OrderStatus.REQUESTED) {
                    log.info("demo-bot.skip-accept orderId={} currentStatus={}",
                            orderId, order == null ? "NULL" : order.getStatus());
                    return false;
                }
                if (dispatchService.currentAssignment(orderId).isPresent()) {
                    log.info("demo-bot.skip-accept orderId={} (real mechanic already assigned)", orderId);
                    return false;
                }

                var now = clock.instant();
                var profile = partners.lockById(botId).orElseThrow();

                // Set initial bot position ~800m - 1km away from customer
                double botLat = order.getPickupLat() + 0.006;
                double botLng = order.getPickupLng() + 0.006;
                profile.updateLocation(botLat, botLng, now);
                profile.setAvailability(Availability.OFFLINE);

                // Close any open broadcast rounds and offers
                dispatchService.closeOpenRounds(orderId, DispatchRound.EndReason.CLAIMED);

                // Create and accept assignment for the Demo Bot
                var assignment = new OrderAssignment(orderId, null, botId, OrderAssignment.Source.BROADCAST, null, now, null);
                assignment.accept(now);
                assignments.save(assignment);

                // Snapshot travel distance & fee just like a real mechanic
                snapshotTravel(order, profile, now);

                // Transition order REQUESTED -> ASSIGNED
                stateMachine.transition(order, OrderStatus.ASSIGNED, botId, ActorType.PARTNER, "Accepted by Demo Bot Mechanic");
                log.info("demo-bot.accepted code={} orderId={} botLat={} botLng={} travelKm={} travelFee={}",
                        order.getOrderCode(), orderId, botLat, botLng, order.getTravelDistanceKm(), order.getTravelFeeSnapshot());
                return true;
            });

            if (Boolean.TRUE.equals(accepted)) {
                // Timeline relative to accept (when delay=20s: +8s -> 28s, +15s -> 35s, +18s -> 38s, +22s -> 42s)
                scheduleForOrder(orderId, 8, () -> stepMoveCloser(orderId));
                scheduleForOrder(orderId, 15, () -> stepArrive(orderId));
                scheduleForOrder(orderId, 18, () -> stepStartChecking(orderId));
                scheduleForOrder(orderId, 22, () -> stepSendQuote(orderId));
            }
        } catch (Exception ex) {
            log.error("demo-bot.step-accept-failed orderId={}", orderId, ex);
        }
    }

    /**
     * Step 3 (28s mark / +8s after accept): Bot moves halfway to customer (lat + 0.003, lng + 0.003).
     */
    private void stepMoveCloser(UUID orderId) {
        try {
            UUID botId = ensureBotAccount();
            tx.executeWithoutResult(status -> {
                var order = orders.lockById(orderId).orElse(null);
                if (order == null || order.getStatus() != OrderStatus.ASSIGNED || !isAssignedToBot(orderId, botId)) {
                    return;
                }
                var now = clock.instant();
                var profile = partners.lockById(botId).orElse(null);
                if (profile != null) {
                    double botLat = order.getPickupLat() + 0.003;
                    double botLng = order.getPickupLng() + 0.003;
                    profile.updateLocation(botLat, botLng, now);
                    log.info("demo-bot.moving code={} botLat={} botLng={}", order.getOrderCode(), botLat, botLng);
                }
            });
        } catch (Exception ex) {
            log.error("demo-bot.step-move-failed orderId={}", orderId, ex);
        }
    }

    /**
     * Step 4 (35s mark / +15s after accept): Bot arrives at customer location (lat + 0.0002, lng + 0.0002)
     * and transitions order to ARRIVED.
     */
    private void stepArrive(UUID orderId) {
        try {
            UUID botId = ensureBotAccount();
            tx.executeWithoutResult(status -> {
                var order = orders.lockById(orderId).orElse(null);
                if (order == null || order.getStatus() != OrderStatus.ASSIGNED || !isAssignedToBot(orderId, botId)) {
                    return;
                }
                var now = clock.instant();
                var profile = partners.lockById(botId).orElse(null);
                if (profile != null) {
                    profile.updateLocation(order.getPickupLat() + 0.0002, order.getPickupLng() + 0.0002, now);
                }
                orderService.arrive(new Actor(botId, Role.PARTNER), orderId);
                log.info("demo-bot.arrived code={}", order.getOrderCode());
            });
        } catch (Exception ex) {
            log.error("demo-bot.step-arrive-failed orderId={}", orderId, ex);
        }
    }

    /**
     * Step 4b (38s mark / +18s after accept): Bot transitions order from ARRIVED to CHECKING.
     */
    private void stepStartChecking(UUID orderId) {
        try {
            UUID botId = ensureBotAccount();
            tx.executeWithoutResult(status -> {
                var order = orders.lockById(orderId).orElse(null);
                if (order == null || order.getStatus() != OrderStatus.ARRIVED || !isAssignedToBot(orderId, botId)) {
                    return;
                }
                orderService.startChecking(new Actor(botId, Role.PARTNER), orderId);
                log.info("demo-bot.checking code={}", order.getOrderCode());
            });
        } catch (Exception ex) {
            log.error("demo-bot.step-checking-failed orderId={}", orderId, ex);
        }
    }

    /**
     * Step 5 (42s mark / +22s after accept): Bot sends an official Quote using the real base price
     * of the requested service (plus any extra services selected by the customer) and transitions
     * the order to WAITING_FOR_APPROVAL.
     */
    private void stepSendQuote(UUID orderId) {
        try {
            UUID botId = ensureBotAccount();
            tx.executeWithoutResult(status -> {
                var order = orders.lockById(orderId).orElse(null);
                if (order == null || order.getStatus().isTerminal() || !isAssignedToBot(orderId, botId)) {
                    return;
                }
                var botActor = new Actor(botId, Role.PARTNER);
                if (order.getStatus() == OrderStatus.ARRIVED) {
                    orderService.startChecking(botActor, orderId);
                }
                if (order.getStatus() != OrderStatus.CHECKING) {
                    return;
                }

                List<QuoteDtos.ItemRequest> items = new ArrayList<>();
                var mainService = catalogCache.byId(order.getRequestedServiceId()).orElse(null);
                if (mainService != null) {
                    items.add(new QuoteDtos.ItemRequest(
                            QuoteItem.Type.LABOR,
                            mainService.getName() + " (Tiền công & vật tư tiêu chuẩn)",
                            BigDecimal.ONE,
                            mainService.getBasePrice(),
                            mainService.getCode()
                    ));
                } else {
                    items.add(new QuoteDtos.ItemRequest(
                            QuoteItem.Type.LABOR,
                            "Dịch vụ cứu hộ lưu động",
                            BigDecimal.ONE,
                            BigDecimal.valueOf(80000),
                            null
                    ));
                }

                if (order.getExtraServiceIds() != null && !order.getExtraServiceIds().isEmpty()) {
                    var extrasMap = catalogCache.byIds(order.getExtraServiceIds());
                    for (UUID extraId : order.getExtraServiceIds()) {
                        var extra = extrasMap.get(extraId);
                        if (extra != null) {
                            items.add(new QuoteDtos.ItemRequest(
                                    QuoteItem.Type.LABOR,
                                    extra.getName(),
                                    BigDecimal.ONE,
                                    extra.getBasePrice(),
                                    extra.getCode()
                            ));
                        }
                    }
                }

                quoteService.send(botActor, orderId, new QuoteDtos.CreateQuoteRequest(items, 60));
                log.info("demo-bot.quote-sent code={} items={}", order.getOrderCode(), items.size());
            });
        } catch (Exception ex) {
            log.error("demo-bot.step-quote-failed orderId={}", orderId, ex);
        }
    }

    /**
     * Step 6 (+15s after customer approves quote): Bot completes the order (IN_PROGRESS -> COMPLETED).
     */
    private void stepCompleteOrder(UUID orderId) {
        try {
            UUID botId = ensureBotAccount();
            tx.executeWithoutResult(status -> {
                var order = orders.lockById(orderId).orElse(null);
                if (order == null || order.getStatus() != OrderStatus.IN_PROGRESS || !isAssignedToBot(orderId, botId)) {
                    return;
                }
                orderService.complete(new Actor(botId, Role.PARTNER), orderId);
                // Keep Bot OFFLINE so it never steals 0s broadcast offers on subsequent orders
                partners.lockById(botId).ifPresent(p -> p.setAvailability(Availability.OFFLINE));
                log.info("demo-bot.completed code={}", order.getOrderCode());
            });
        } catch (Exception ex) {
            log.error("demo-bot.step-complete-failed orderId={}", orderId, ex);
        } finally {
            cancelScheduledTasks(orderId);
        }
    }

    // ------------------------------------------------------------------ Internal Helpers

    private boolean isAssignedToBot(UUID orderId, UUID botId) {
        return dispatchService.currentAssignment(orderId)
                .map(a -> a.getPartnerId().equals(botId))
                .orElse(false);
    }

    private void snapshotTravel(RescueOrder order, PartnerProfile profile, java.time.Instant now) {
        if (profile.getCurrentLat() == null || profile.getCurrentLng() == null) return;
        var cfg = travelFees.findActiveGlobal(now).orElse(null);
        if (cfg == null) return;
        double km = GeoDistance.haversineKm(profile.getCurrentLat(), profile.getCurrentLng(),
                order.getPickupLat(), order.getPickupLng());
        var travel = TravelFeeCalculator.compute(km, cfg.getFreeKm(), cfg.getPerKmAmount());
        order.recordTravel(travel.distanceKm(), travel.fee(), cfg.getId());
    }

    private void scheduleForOrder(UUID orderId, int delaySec, Runnable task) {
        ScheduledFuture<?> future = scheduler.schedule(task, delaySec, TimeUnit.SECONDS);
        activeTimers.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(future);
    }

    private void cancelScheduledTasks(UUID orderId) {
        List<ScheduledFuture<?>> tasks = activeTimers.remove(orderId);
        if (tasks != null) {
            for (ScheduledFuture<?> f : tasks) {
                f.cancel(false);
            }
        }
    }
}
