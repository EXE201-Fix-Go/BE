package com.fixgo.admin;

import com.fixgo.catalog.ServiceCatalogCache;
import com.fixgo.common.Actor;
import com.fixgo.dispatch.DispatchService;
import com.fixgo.order.OrderDtos;
import com.fixgo.order.OrderService;
import com.fixgo.order.RescueOrderRepository;
import com.fixgo.partner.*;
import com.fixgo.payment.PaymentService;
import com.fixgo.payment.Review;
import com.fixgo.payment.ReviewRepository;
import com.fixgo.quote.QuoteDtos;
import com.fixgo.quote.QuoteItem;
import com.fixgo.quote.QuoteService;
import com.fixgo.user.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Dev-only sample data (fixgo.dev-seed=true / DEV_SEED=true): test accounts and a few orders in different
 * states so the FE has something to show. Idempotent — skipped when the test customer already exists.
 * Orders are created through the real services so every invariant (history, dispatch, quotes) holds.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "fixgo.dev-seed", havingValue = "true")
public class DevSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);
    public static final String CUSTOMER_PHONE = "+84901000001";
    public static final String MECHANIC_PHONE = "+84902000001";
    public static final String MECHANIC2_PHONE = "+84902000002";
    public static final String SHOP_OWNER_PHONE = "+84903000001";
    public static final String SHOP_STAFF_PHONE = "+84903000002";
    /** Làng Đại học, Thủ Đức — pilot area (BRD). */
    private static final double LAT = 10.8700, LNG = 106.8030;

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PartnerProfileRepository profiles;
    private final PartnerServiceOfferRepository offers;
    private final PartnerDocumentRepository documents;
    private final ServiceCatalogCache catalog;
    private final OrderService orders;
    private final RescueOrderRepository orderRepo;
    private final DispatchService dispatch;
    private final QuoteService quotes;
    private final PaymentService payments;
    private final ReviewRepository reviews;
    private final Clock clock;

    public DevSeed(UserRepository users, UserIdentityRepository identities, PartnerProfileRepository profiles,
                   PartnerServiceOfferRepository offers, PartnerDocumentRepository documents,
                   ServiceCatalogCache catalog, OrderService orders, RescueOrderRepository orderRepo,
                   DispatchService dispatch, QuoteService quotes, PaymentService payments, ReviewRepository reviews,
                   Clock clock) {
        this.users = users;
        this.identities = identities;
        this.profiles = profiles;
        this.offers = offers;
        this.documents = documents;
        this.catalog = catalog;
        this.orders = orders;
        this.orderRepo = orderRepo;
        this.dispatch = dispatch;
        this.quotes = quotes;
        this.payments = payments;
        this.reviews = reviews;
        this.clock = clock;
    }

    /** Deliberately NOT one big transaction: each service call must see the committed state of the previous one. */
    @Override
    public void run(ApplicationArguments args) {
        var now = clock.instant();
        // Accounts are (re)asserted on every start: existing ones are approved and put online, never duplicated.
        var customer = user(Role.CUSTOMER, "Trần Thị Mai Lan", CUSTOMER_PHONE);
        var mechanic = partner("Nguyễn Văn Tuấn", MECHANIC_PHONE, PartnerType.INDIVIDUAL, null, null,
                List.of("tire-patch", "tire-pump", "tube-replace", "battery-jump", "chain-fix"), LAT + 0.004, LNG);
        partner("Lê Văn Bình", MECHANIC2_PHONE, PartnerType.INDIVIDUAL, null, null,
                List.of("tire-patch", "oil-change", "chain-clean", "towing"), LAT - 0.006, LNG + 0.003);
        var shop = partner("Phạm Quốc Hùng", SHOP_OWNER_PHONE, PartnerType.SHOP, null, "Tiệm sửa xe Hùng Phát",
                List.of("tire-patch", "tube-replace", "oil-change", "towing"), LAT + 0.010, LNG - 0.004);
        partner("Võ Minh Khang", SHOP_STAFF_PHONE, PartnerType.SHOP_STAFF, shop.getUserId(), null,
                List.of("tire-patch", "tube-replace", "oil-change"), LAT + 0.011, LNG - 0.004);

        var cust = new Actor(customer.getId(), Role.CUSTOMER);
        var mech = new Actor(mechanic.getUserId(), Role.PARTNER);
        if (!orderRepo.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).isEmpty()) {
            log.info("DevSeed: accounts asserted; sample orders already exist, skipping");
            return;
        }

        // 1. A completed, paid and reviewed order (history / invoice screens).
        var done = orderThrough(cust, mech, "tire-patch", "Cổng KTX khu B, Làng Đại học, Thủ Đức", "Cán đinh bánh sau");
        quotes.send(mech, done, new QuoteDtos.CreateQuoteRequest(List.of(
                new QuoteDtos.ItemRequest(QuoteItem.Type.LABOR, "Vá xe lưu động", BigDecimal.ONE, new BigDecimal("80000"), "tire-patch"),
                new QuoteDtos.ItemRequest(QuoteItem.Type.PART, "Miếng vá nấm", new BigDecimal("2"), new BigDecimal("10000"), null)), null));
        var sent = orders.get(cust, done).quote();
        quotes.approve(cust, done, sent.id());
        orders.complete(mech, done);
        payments.confirm(cust, done);
        reviews.save(new Review(done, mechanic.getUserId(), 5, "Thợ tới nhanh, vá gọn gàng.", now));

        // 2. Cancelled after arrival → call-out fee still due (BRD §9 decision).
        var cancelled = orderThrough(cust, mech, "battery-jump", "Ngã tư Thủ Đức, gần Vincom", "Đề không nổ");
        orders.cancel(cust, cancelled, "Xe tự nổ máy lại được");

        // 3. No partner available: far from every online partner → NO_PARTNER_FOUND after all rounds.
        var far = orders.create(cust, new OrderDtos.CreateOrderRequest("towing", null, "Hồ Gươm, Hoàn Kiếm, Hà Nội",
                "Xe chết máy giữa đường", null, 21.0285, 105.8542, "Honda Vision đỏ", null, null));
        orders.confirm(cust, far.id());

        log.info("DevSeed: created test accounts (customer {}, mechanic {}, shop {}) and 3 sample orders",
                CUSTOMER_PHONE, MECHANIC_PHONE, SHOP_OWNER_PHONE);
    }

    /** create → confirm → broadcast → the given mechanic accepts → arrives → checks. */
    private UUID orderThrough(Actor cust, Actor mech, String service, String address, String note) {
        var order = orders.create(cust, new OrderDtos.CreateOrderRequest(service, null, address, note,
                List.of("https://images.unsplash.com/photo-1558981403-c5f9899a28bc?w=600"), LAT, LNG,
                "Honda Vision đỏ, biển 59-V1 824.96", null, null));
        orders.confirm(cust, order.id());
        var offer = dispatch.listOffers(mech).stream().filter(o -> o.orderId().equals(order.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Seed mechanic did not receive the broadcast"));
        dispatch.accept(mech, offer.assignmentId());
        orders.arrive(mech, order.id());
        orders.startChecking(mech, order.id());
        return order.id();
    }

    /** Reuses an existing account for the phone (e.g. created by an earlier manual test) instead of failing. */
    private User user(Role role, String name, String phone) {
        var now = clock.instant();
        var existing = identities.findByProviderAndProviderUid(IdentityProvider.PHONE, phone).orElse(null);
        if (existing != null) {
            var u = existing.getUser();
            if (u.getRole() != Role.ADMIN && u.getRole() != role) u.changeRole(role);
            if (u.getFullName() == null) u.rename(name);
            return users.save(u);
        }
        var u = users.save(new User(role, name, now));
        identities.save(new UserIdentity(u, IdentityProvider.PHONE, phone, true, now, now));
        return u;
    }

    private PartnerProfile partner(String name, String phone, PartnerType type, UUID parentShopId, String shopName,
                                   List<String> serviceCodes, double lat, double lng) {
        var now = clock.instant();
        var u = user(Role.PARTNER, name, phone);
        var found = profiles.findById(u.getId()).orElse(null);
        if (found != null) {
            found.verify(VerificationStatus.APPROVED, null, now);
            found.updatePresence(Availability.ONLINE, lat, lng, now);
            return profiles.save(found);
        }
        var p = new PartnerProfile(u.getId(), type, parentShopId, shopName);
        p.verify(VerificationStatus.APPROVED, null, now);
        p.updatePresence(Availability.ONLINE, lat, lng, now);
        profiles.save(p);
        for (var code : serviceCodes) {
            catalog.activeByCode(code).ifPresent(s -> offers.save(new PartnerServiceOffer(u.getId(), s.getId())));
        }
        for (var doc : List.of("ID_FRONT", "ID_BACK", "SELFIE")) {
            var d = new PartnerDocument(u.getId(), doc, "kyc/" + phone + "/" + doc.toLowerCase() + ".jpg", now);
            d.review("APPROVED", null, now);
            documents.save(d);
        }
        return p;
    }
}
