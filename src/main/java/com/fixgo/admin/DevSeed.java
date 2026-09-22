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
import org.springframework.beans.factory.annotation.Value;
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
    public static final String CUSTOMER2_PHONE = "+84901000002";
    public static final String CUSTOMER3_PHONE = "+84901000003";
    public static final String CUSTOMER4_PHONE = "+84901000004";
    public static final String MECHANIC_PHONE = "+84902000001";
    public static final String MECHANIC2_PHONE = "+84902000002";
    public static final String MECHANIC3_PHONE = "+84902000003";
    public static final String MECHANIC4_PHONE = "+84902000004";
    public static final String MECHANIC5_PHONE = "+84902000005";
    public static final String SHOP_OWNER_PHONE = "+84903000001";
    public static final String SHOP_STAFF_PHONE = "+84903000002";
    public static final String SHOP2_OWNER_PHONE = "+84903000003";
    public static final String SHOP2_STAFF_PHONE = "+84903000004";
    /**
     * Điểm gốc để seed thợ (mặc định: Làng Đại học, Thủ Đức — pilot BRD). Đổi qua env DEV_SEED_LAT / DEV_SEED_LNG
     * để thợ mẫu xuất hiện gần nơi bạn test — nếu không, đơn từ GPS thật xa vùng này sẽ luôn NO_PARTNER_FOUND.
     */
    @Value("${DEV_SEED_LAT:10.8700}")
    private double LAT;
    @Value("${DEV_SEED_LNG:106.8030}")
    private double LNG;
    /** Đặt Trịnh Văn Sơn (mechanic3) tại một điểm cụ thể để test phí di chuyển. 0 = dùng offset mặc định. */
    @Value("${DEV_SEED_MECH3_LAT:0}")
    private double mech3Lat;
    @Value("${DEV_SEED_MECH3_LNG:0}")
    private double mech3Lng;

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
        var mechanic2 = partner("Lê Văn Bình", MECHANIC2_PHONE, PartnerType.INDIVIDUAL, null, null,
                List.of("tire-patch", "oil-change", "chain-clean", "towing"), LAT - 0.006, LNG + 0.003);
        var shop = partner("Phạm Quốc Hùng", SHOP_OWNER_PHONE, PartnerType.SHOP, null, "Tiệm sửa xe Hùng Phát",
                List.of("tire-patch", "tube-replace", "oil-change", "towing"), LAT + 0.010, LNG - 0.004);
        var shopStaff = partner("Võ Minh Khang", SHOP_STAFF_PHONE, PartnerType.SHOP_STAFF, shop.getUserId(), null,
                List.of("tire-patch", "tube-replace", "oil-change"), LAT + 0.011, LNG - 0.004);

        // More sample accounts for a fuller demo dataset (customers + individual mechanics + a second shop).
        var customer2 = user(Role.CUSTOMER, "Đỗ Thị Hồng", CUSTOMER2_PHONE);
        var customer3 = user(Role.CUSTOMER, "Bùi Anh Khoa", CUSTOMER3_PHONE);
        var customer4 = user(Role.CUSTOMER, "Ngô Thị Thu Hà", CUSTOMER4_PHONE);
        double m3Lat = mech3Lat != 0 ? mech3Lat : LAT + 0.005;
        double m3Lng = mech3Lng != 0 ? mech3Lng : LNG + 0.002;
        var mechanic3 = partner("Trịnh Văn Sơn", MECHANIC3_PHONE, PartnerType.INDIVIDUAL, null, null,
                List.of("battery-jump", "tire-pump", "tire-patch", "light-fix"), m3Lat, m3Lng);
        var mechanic4 = partner("Đặng Hoàng Nam", MECHANIC4_PHONE, PartnerType.INDIVIDUAL, null, null,
                List.of("tire-patch", "spark-plug", "oil-change"), LAT - 0.003, LNG - 0.005);
        var mechanic5 = partner("Phan Thanh Tùng", MECHANIC5_PHONE, PartnerType.INDIVIDUAL, null, null,
                List.of("chain-fix", "tire-patch", "brake-fix", "battery-jump"), LAT + 0.007, LNG - 0.002);
        var shop2 = partner("Hoàng Văn Phúc", SHOP2_OWNER_PHONE, PartnerType.SHOP, null, "Tiệm xe Phúc Thành",
                List.of("tube-replace", "tire-patch", "oil-change", "towing", "brake-fix"), LAT - 0.008, LNG + 0.006);
        var shop2Staff = partner("Lý Gia Bảo", SHOP2_STAFF_PHONE, PartnerType.SHOP_STAFF, shop2.getUserId(), null,
                List.of("tire-patch", "oil-change", "tube-replace"), LAT - 0.008, LNG + 0.007);

        var cust = new Actor(customer.getId(), Role.CUSTOMER);
        var mech = new Actor(mechanic.getUserId(), Role.PARTNER);

        // Original sample orders (keyed off the first customer so they are created exactly once).
        if (orderRepo.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).isEmpty()) {
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
        }

        // Extra orders covering the remaining active states — each assigned to a distinct partner so nobody is
        // BUSY when the next broadcast goes out (an accepted partner drops out of candidate matching).
        // Keyed off customer2 so this block runs exactly once, independently of the original orders above.
        if (orderRepo.findByCustomerIdOrderByCreatedAtDesc(customer2.getId()).isEmpty()) {
            var cust2 = new Actor(customer2.getId(), Role.CUSTOMER);
            var cust3 = new Actor(customer3.getId(), Role.CUSTOMER);
            var cust4 = new Actor(customer4.getId(), Role.CUSTOMER);
            var mech2 = new Actor(mechanic2.getUserId(), Role.PARTNER);
            var mech3 = new Actor(mechanic3.getUserId(), Role.PARTNER);
            var mech4 = new Actor(mechanic4.getUserId(), Role.PARTNER);
            var mech5 = new Actor(mechanic5.getUserId(), Role.PARTNER);
            var shopStaffActor = new Actor(shopStaff.getUserId(), Role.PARTNER);
            var shop2Actor = new Actor(shop2.getUserId(), Role.PARTNER);
            var shop2StaffActor = new Actor(shop2Staff.getUserId(), Role.PARTNER);

            // 4. Just accepted, partner en route (ASSIGNED).
            assignOrder(cust2, mech2, "oil-change", "Chung cư Sky9, Phú Hữu, Thủ Đức", "Xe ra khói, nghi cạn nhớt");

            // 5. Partner has arrived, not yet inspecting (ARRIVED).
            var arrived = assignOrder(cust3, mech3, "battery-jump", "Trước cổng Vincom Thủ Đức", "Đề yếu, đèn mờ");
            orders.arrive(mech3, arrived);

            // 6. Partner inspecting the vehicle (CHECKING).
            orderThrough(cust3, mech4, "tire-patch", "Đường số 4, KDC Vạn Phúc", "Bánh trước xì hơi từ từ");

            // 7. Initial quote sent, waiting on the customer (WAITING_FOR_APPROVAL).
            var waiting = orderThrough(cust4, mech5, "chain-fix", "Ngã ba Tăng Nhơn Phú", "Sên kêu to, nhảy sên");
            quote(mech5, waiting, "chain-fix", "Tăng / chỉnh sên", "60000");

            // 8. Quote approved, repair underway (IN_PROGRESS).
            var inProgress = orderThrough(cust4, shop2Actor, "tube-replace", "Đường Bưng Ông Thoàn, Phú Hữu", "Thủng ruột, cần thay");
            quote(shop2Actor, inProgress, "tube-replace", "Thay ruột xe tay ga", "160000");
            approveLatest(cust4, inProgress);

            // 9. Extra work found mid-repair, waiting on the customer again (ADDITIONAL_QUOTE).
            var additional = orderThrough(cust2, shop2StaffActor, "oil-change", "Hẻm 40 Tô Vĩnh Diện", "Thay nhớt định kỳ");
            quote(shop2StaffActor, additional, "oil-change", "Thay nhớt tận nơi", "180000");
            approveLatest(cust2, additional);
            quote(shop2StaffActor, additional, "oil-change", "Phát sinh: thay lọc nhớt", "90000");

            // 10. Repair paused, waiting on a part (PAUSED).
            var paused = orderThrough(cust3, shopStaffActor, "tire-patch", "ĐH Sư phạm Kỹ thuật, cổng sau", "Cán đinh, lốp yếu");
            quote(shopStaffActor, paused, "tire-patch", "Vá xe lưu động", "80000");
            approveLatest(cust3, paused);
            orders.pause(shopStaffActor, paused, "Chờ khách mua thêm ruột dự phòng");

            // 11. A second completed, paid and reviewed order (fuller history for the shop owner).
            var mechShop = new Actor(shop.getUserId(), Role.PARTNER);
            var done2 = orderThrough(cust2, mechShop, "tire-patch", "Đường Man Thiện, Tăng Nhơn Phú A", "Xẹp lốp sau");
            quote(mechShop, done2, "tire-patch", "Vá xe lưu động", "80000");
            approveLatest(cust2, done2);
            orders.complete(mechShop, done2);
            reviews.save(new Review(done2, shop.getUserId(), 4, "Ổn, giá hợp lý.", now));
        }

        log.info("DevSeed: accounts asserted (4 customers, 9 partners); sample orders across all order states ensured");
    }

    /** create → confirm → broadcast → the given partner accepts. Stops at ASSIGNED. */
    private UUID assignOrder(Actor cust, Actor mech, String service, String address, String note) {
        var order = orders.create(cust, new OrderDtos.CreateOrderRequest(service, null, address, note,
                List.of("https://images.unsplash.com/photo-1558981403-c5f9899a28bc?w=600"), LAT, LNG,
                "Honda Vision đỏ, biển 59-V1 824.96", null, null));
        orders.confirm(cust, order.id());
        var offer = dispatch.listOffers(mech).stream().filter(o -> o.orderId().equals(order.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Seed partner did not receive the broadcast"));
        dispatch.accept(mech, offer.assignmentId());
        return order.id();
    }

    /** Sends a quote: INITIAL from CHECKING (→ WAITING_FOR_APPROVAL) or ADDITIONAL from IN_PROGRESS (→ ADDITIONAL_QUOTE). */
    private void quote(Actor partner, UUID orderId, String serviceCode, String laborDesc, String laborPrice) {
        quotes.send(partner, orderId, new QuoteDtos.CreateQuoteRequest(List.of(
                new QuoteDtos.ItemRequest(QuoteItem.Type.LABOR, laborDesc, BigDecimal.ONE, new BigDecimal(laborPrice), serviceCode)), null));
    }

    /** Customer approves the order's latest quote (→ IN_PROGRESS). */
    private void approveLatest(Actor cust, UUID orderId) {
        quotes.approve(cust, orderId, orders.get(cust, orderId).quote().id());
    }

    /** assignOrder → arrives → checks. Stops at CHECKING. */
    private UUID orderThrough(Actor cust, Actor mech, String service, String address, String note) {
        var id = assignOrder(cust, mech, service, address, note);
        orders.arrive(mech, id);
        orders.startChecking(mech, id);
        return id;
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
