package com.fixgo.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("fixgo.auth")
public record AuthProperties(@NotBlank String issuer, @NotBlank String audience, String jwtSecret,
                             @NotNull Duration accessTokenTtl, @NotNull Duration refreshTokenTtl,
                             @NotNull Duration otpTtl, @Min(1) int otpMaxAttempts,
                             @Min(1) int otpMaxPerTargetPerHour, @Min(1) int otpMaxPerIpPerHour,
                             boolean otpDevEcho) {
    public AuthProperties {
        if (accessTokenTtl != null && accessTokenTtl.toSeconds() < 1) {
            throw new IllegalArgumentException("Access token TTL must be at least one second.");
        }
        if (refreshTokenTtl != null && refreshTokenTtl.toSeconds() < 1) {
            throw new IllegalArgumentException("Refresh token TTL must be at least one second.");
        }
        if (otpTtl != null && otpTtl.toSeconds() < 1) {
            throw new IllegalArgumentException("OTP TTL must be at least one second.");
        }
    }
}
