package com.fixgo.partner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PartnerDtos {
    private PartnerDtos() { }

    /** POST /partner-registration — the FE KYC screen: identity docs (CCCD front/back + selfie) and skills. */
    public record RegisterRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotNull PartnerType partnerType,          // INDIVIDUAL or SHOP (staff are invited by the shop)
            @Size(max = 120) String shopName,
            @NotEmpty List<@NotBlank String> serviceCodes,
            @NotEmpty @Valid List<DocumentUpload> documents) { }

    public record DocumentUpload(@NotBlank @Pattern(regexp = "^(ID_FRONT|ID_BACK|SELFIE|LICENSE|OTHER)$") String documentType,
                                 @NotBlank @Size(max = 500) String storageKey) { }

    public record PresenceRequest(@NotNull Availability availability,
                                  @DecimalMin("-90") @DecimalMax("90") Double lat,
                                  @DecimalMin("-180") @DecimalMax("180") Double lng) { }

    /** POST /partner/shop/staff — shop owner invites a mechanic by phone (BR06/RB-12: admin still verifies). */
    public record InviteStaffRequest(@NotBlank @Size(max = 20) String phone,
                                     @NotBlank @Size(max = 100) String fullName) { }

    public record VerifyRequest(@NotNull VerificationStatus status) { }

    public record ProfileResponse(UUID userId, String fullName, String phone, PartnerType partnerType,
                                  UUID parentShopId, String shopName, VerificationStatus verificationStatus,
                                  Availability availability, OperationalStatus operationalStatus,
                                  Double lat, Double lng, Instant locationUpdatedAt,
                                  List<String> serviceCodes, List<DocumentResponse> documents) { }

    public record DocumentResponse(UUID id, String documentType, String reviewStatus) { }

    /** GET /partner/stats — dashboard tiles; "today" is Vietnam time (BRD pilot in HCMC). */
    public record StatsResponse(long completedToday, java.math.BigDecimal earnedToday, long completedTotal,
                                Double averageRating, long reviewCount, long activeJobs) { }

    /** GET /partner/dashboard — everything the partner home screen polls, in ONE round trip. */
    public record DashboardResponse(ProfileResponse profile, List<com.fixgo.dispatch.DispatchDtos.OfferResponse> offers,
                                    List<com.fixgo.dispatch.DispatchDtos.OfferResponse> jobs, StatsResponse stats) { }

    public record StaffResponse(UUID userId, String fullName, String phone, VerificationStatus verificationStatus,
                                Availability availability) { }
}
