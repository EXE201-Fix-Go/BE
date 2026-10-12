package com.fixgo.module.admin.service;

import com.fixgo.module.admin.dto.AdminDtos;
import com.fixgo.module.admin.repository.AdminAccountReportRepository;
import com.fixgo.module.admin.repository.AdminPartnerQueryRepository;
import com.fixgo.module.admin.repository.AdminReportRepository;
import com.fixgo.module.catalog.entity.ServiceCatalog;
import com.fixgo.module.catalog.service.ServiceCatalogCache;
import com.fixgo.module.dispatch.entity.OrderAssignment;
import com.fixgo.module.iam.entity.User;
import com.fixgo.module.iam.enums.AccountStatus;
import com.fixgo.module.iam.enums.Role;
import com.fixgo.module.iam.repository.UserRepository;
import com.fixgo.module.order.entity.RescueOrder;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.partner.entity.PartnerProfile;
import com.fixgo.module.partner.enums.Availability;
import com.fixgo.module.partner.enums.VerificationStatus;
import com.fixgo.module.payment.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operations dashboard: aggregated numbers plus partner (KYC) and order listings. Everything is read-only; the
 * actions (lock an account, approve a partner) stay in the existing {@link AdminUserService} /
 * {@code PartnerProfileService.verify}.
 */
@Service
public class AdminDashboardService {
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    /** The partner "of record" for an order: still accepted, or ended after finishing/cancelling. */
    private static final List<OrderAssignment.Status> PARTNER_OF_RECORD =
            List.of(OrderAssignment.Status.ACCEPTED, OrderAssignment.Status.ENDED);

    private final AdminReportRepository orders;
    private final AdminAccountReportRepository accounts;
    private final AdminPartnerQueryRepository partners;
    private final UserRepository users;
    private final ServiceCatalogCache catalog;
    private final Clock clock;

    public AdminDashboardService(AdminReportRepository orders, AdminAccountReportRepository accounts,
                                 AdminPartnerQueryRepository partners, UserRepository users,
                                 ServiceCatalogCache catalog, Clock clock) {
        this.orders = orders;
        this.accounts = accounts;
        this.partners = partners;
        this.users = users;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminDtos.OverviewResponse overview() {
        Instant startOfToday = LocalDate.now(clock.withZone(VN)).atStartOfDay(VN).toInstant();
        return new AdminDtos.OverviewResponse(userCounts(), partnerCounts(), orderCounts(startOfToday),
                revenue(startOfToday), clock.instant());
    }

    @Transactional(readOnly = true)
    public AdminDtos.PageResponse<AdminDtos.PartnerRow> partners(VerificationStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size);
        Page<PartnerProfile> result = status == null
                ? partners.pageNewestFirst(pageable)
                : partners.pageNewestFirstByVerification(status, pageable);
        List<UUID> ids = result.getContent().stream().map(PartnerProfile::getUserId).toList();

        Map<UUID, String> names = new HashMap<>();
        Map<UUID, String> phones = new HashMap<>();
        Map<UUID, List<AdminDtos.DocumentRow>> documents = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User u : users.findAllById(ids)) names.put(u.getId(), u.getFullName());
            for (Object[] row : accounts.primaryUidsFor(ids)) phones.put((UUID) row[0], (String) row[1]);
            for (Object[] row : partners.documentsFor(ids)) {
                documents.computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                        .add(new AdminDtos.DocumentRow((String) row[1], (String) row[2]));
            }
        }
        var rows = result.getContent().stream().map(p -> new AdminDtos.PartnerRow(p.getUserId(),
                names.get(p.getUserId()), phones.get(p.getUserId()), p.getPartnerType(), p.getShopName(),
                p.getParentShopId(), p.getVerificationStatus(), p.getAvailability(), p.getVerifiedAt(),
                documents.getOrDefault(p.getUserId(), List.of()))).toList();
        return new AdminDtos.PageResponse<>(rows, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminDtos.PageResponse<AdminDtos.OrderRow> orders(OrderStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size);
        Page<RescueOrder> result = status == null
                ? orders.findAllByOrderByCreatedAtDescIdAsc(pageable)
                : orders.findByStatusOrderByCreatedAtDescIdAsc(status, pageable);
        List<RescueOrder> content = result.getContent();
        List<UUID> ids = content.stream().map(RescueOrder::getId).toList();

        Map<UUID, String> partnerNames = new HashMap<>();
        Map<UUID, BigDecimal> paid = new HashMap<>();
        Map<UUID, ServiceCatalog> services = Map.of();
        if (!ids.isEmpty()) {
            // Oldest assignment first, so a later reassignment overwrites the earlier partner name.
            for (Object[] row : orders.partnerNamesForOrders(ids, PARTNER_OF_RECORD)) {
                partnerNames.put((UUID) row[0], (String) row[1]);
            }
            for (Object[] row : orders.paidAmountsForOrders(ids, Payment.Status.CONFIRMED)) {
                paid.put((UUID) row[0], (BigDecimal) row[1]);
            }
            var serviceIds = new HashSet<UUID>();
            content.forEach(o -> serviceIds.add(o.getRequestedServiceId()));
            services = catalog.byIds(serviceIds);
        }
        final Map<UUID, ServiceCatalog> serviceById = services;
        var rows = content.stream().map(o -> {
            ServiceCatalog service = serviceById.get(o.getRequestedServiceId());
            return new AdminDtos.OrderRow(o.getId(), o.getOrderCode(), o.getStatus(),
                    service == null ? null : service.getName(), o.getContactName(), o.getContactPhone(),
                    partnerNames.get(o.getId()), o.getCallOutFeeSnapshot(), o.getTravelFeeSnapshot(),
                    paid.get(o.getId()), o.getCreatedAt(), o.getCompletedAt());
        }).toList();
        return new AdminDtos.PageResponse<>(rows, page, size, result.getTotalElements(), result.getTotalPages());
    }

    // ------------------------------------------------------------------ overview parts

    private AdminDtos.UserCounts userCounts() {
        long customers = 0, partnerAccounts = 0, admins = 0, locked = 0;
        for (Object[] row : accounts.countByRoleAndStatus()) {
            long n = ((Number) row[2]).longValue();
            switch ((Role) row[0]) {
                case CUSTOMER -> customers += n;
                case PARTNER -> partnerAccounts += n;
                case ADMIN -> admins += n;
            }
            if (row[1] == AccountStatus.LOCKED) locked += n;
        }
        return new AdminDtos.UserCounts(customers + partnerAccounts + admins, customers, partnerAccounts, admins, locked);
    }

    private AdminDtos.PartnerCounts partnerCounts() {
        var byStatus = new EnumMap<VerificationStatus, Long>(VerificationStatus.class);
        for (Object[] row : partners.countByVerification()) {
            byStatus.put((VerificationStatus) row[0], ((Number) row[1]).longValue());
        }
        long online = partners.countByVerificationAndAvailability(VerificationStatus.APPROVED, Availability.ONLINE);
        return new AdminDtos.PartnerCounts(byStatus.getOrDefault(VerificationStatus.PENDING, 0L),
                byStatus.getOrDefault(VerificationStatus.APPROVED, 0L),
                byStatus.getOrDefault(VerificationStatus.REJECTED, 0L), online);
    }

    private AdminDtos.OrderCounts orderCounts(Instant startOfToday) {
        var byStatus = new EnumMap<OrderStatus, Long>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) byStatus.put(s, 0L);   // all 14 states, even when empty
        long total = 0;
        for (Object[] row : orders.countByStatus()) {
            long n = ((Number) row[1]).longValue();
            byStatus.put((OrderStatus) row[0], n);
            total += n;
        }
        return new AdminDtos.OrderCounts(total, orders.countCreatedSince(startOfToday), byStatus);
    }

    private AdminDtos.RevenueFigures revenue(Instant startOfToday) {
        BigDecimal total = orders.sumPaymentsConfirmedSince(Payment.Status.CONFIRMED, Instant.EPOCH);
        BigDecimal today = orders.sumPaymentsConfirmedSince(Payment.Status.CONFIRMED, startOfToday);
        BigDecimal rate = orders.activeGlobalCommissionRate().orElse(null);
        BigDecimal commission = rate == null ? null : total.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        return new AdminDtos.RevenueFigures(total, today, rate, commission);
    }
}
