package com.fixgo.module.admin.dto;

import com.fixgo.module.partner.enums.VerificationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An individual partner whose identity the admin checked outside the system (no KYC files are stored). */
public final class TrustedPartnerDtos {
    private TrustedPartnerDtos() { }

    public record CreateRequest(@NotBlank @Size(max = 100) String fullName,
                                @NotBlank @Size(max = 30) String phone,
                                @NotEmpty @Size(max = 20) List<@NotBlank String> serviceCodes,
                                @Size(max = 2000) String avatarUrl) { }

    /** {@code accountCreated=false}: the phone already had a CUSTOMER account, which became this partner. */
    public record Response(UUID userId, String fullName, String phone, String avatarUrl,
                           VerificationStatus verificationStatus, UUID verifiedBy, Instant verifiedAt,
                           List<String> serviceCodes, boolean accountCreated) { }
}
