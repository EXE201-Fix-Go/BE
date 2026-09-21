package com.fixgo.auth;

import com.fixgo.user.AppRole;
import com.fixgo.user.UserResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() { }

    /** POST /auth/otp — matches FE requestOtp(phone). */
    public record OtpRequest(@NotBlank @Size(max = 20) String phone) { }

    /** devCode is only populated when fixgo.auth.otp-dev-echo=true (no SMS provider yet). */
    public record OtpRequestResult(UUID otpId, long expiresInSec, String devCode) { }

    /** POST /auth/otp/verify — matches FE verifyOtp(otpId, code). Device info is optional for web. */
    public record OtpVerifyRequest(@NotNull UUID otpId,
                                   @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
                                   @Size(max = 200) String deviceFingerprint,
                                   DevicePlatform platform) { }

    public record RefreshRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String refreshToken) { }

    /** Token envelope; `role` is the app-level code the FE routes on (CUSTOMER | P_IND | P_SHOP | P_STAFF | ADMIN). */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                Instant refreshExpiresAt, AppRole role, UserResponse user) { }
}
