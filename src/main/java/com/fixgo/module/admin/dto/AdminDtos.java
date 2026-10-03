package com.fixgo.module.admin.dto;

import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.partner.enums.Availability;
import com.fixgo.module.partner.enums.PartnerType;
import com.fixgo.module.partner.enums.VerificationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models for the admin dashboard. Users and KYC decisions reuse the existing admin endpoints. */
public final class AdminDtos {
    private AdminDtos() { }

    /** Same page envelope as AdminUserService.UserPage, so the web client can share one type. */
    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) { }

    public record UserCounts(long total, long customers, long partners, long admins, long locked) { }

    /** Partners by KYC state; {@code onlineApproved} = approved partners currently ONLINE. */
    public record PartnerCounts(long pending, long approved, long rejected, long onlineApproved) { }

    /** {@code byStatus} always contains all 14 order states (zero when empty). */
    public record OrderCounts(long total, long today, Map<OrderStatus, Long> byStatus) { }

    /**
     * Cash collected so far. {@code commissionRate}/{@code estimatedCommission} are null when no active
     * GLOBAL commission config exists (RB-23: the rate lives in the database, never in code).
     */
    public record RevenueFigures(BigDecimal confirmedTotal, BigDecimal confirmedToday,
                                 BigDecimal commissionRate, BigDecimal estimatedCommission) { }

    public record OverviewResponse(UserCounts users, PartnerCounts partners, OrderCounts orders,
                                   RevenueFigures revenue, Instant generatedAt) { }

    /** KYC evidence is kept in private storage; only its type and review state are exposed here. */
    public record DocumentRow(String documentType, String reviewStatus) { }

    public record PartnerRow(UUID userId, String fullName, String phone, PartnerType partnerType, String shopName,
                             UUID parentShopId, VerificationStatus verificationStatus, Availability availability,
                             Instant verifiedAt, List<DocumentRow> documents) { }

    public record OrderRow(UUID id, String orderCode, OrderStatus status, String serviceName, String customerName,
                           String customerPhone, String partnerName, BigDecimal callOutFee, BigDecimal travelFee,
                           BigDecimal paidAmount, Instant createdAt, Instant completedAt) { }
}
