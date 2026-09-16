package com.fixgo.auth;

import com.fixgo.common.ValidPassword;
import com.fixgo.user.User;
import com.fixgo.user.UserResponse;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class AuthDtos {
    private AuthDtos() { }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @ValidPassword String password,
            @NotBlank @Size(max = 100) String fullName,
            @Pattern(regexp = "^(0[35789][0-9]{8}|\\+[1-9][0-9]{7,14})$",
                    message = "Use a Vietnamese mobile number or international format, for example +84901234567.")
            String phoneNumber) {
        public RegisterRequest {
            email = User.normalizeEmail(email);
            fullName = fullName == null ? null : fullName.strip();
        }
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 256) String password) {
        public LoginRequest { email = User.normalizeEmail(email); }
    }

    public record RefreshRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String refreshToken) { }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType,
                                long expiresIn, Instant refreshExpiresAt, UserResponse user) { }

    public record ChangePasswordRequest(@NotBlank @Size(max = 256) String currentPassword,
                                        @ValidPassword String newPassword) { }
}
