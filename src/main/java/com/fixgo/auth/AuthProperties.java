package com.fixgo.auth;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("fixgo.auth")
public record AuthProperties(@NotBlank String issuer, @NotBlank String audience, String jwtSecret,
                             @NotNull Duration accessTokenTtl, @NotNull Duration refreshTokenTtl,
                             @Min(1) int maxLoginFailures, @NotNull Duration loginLockDuration,
                             String localKeyFile) {
    public AuthProperties {
        if (accessTokenTtl != null && (accessTokenTtl.isNegative() || accessTokenTtl.toSeconds() < 1)) {
            throw new IllegalArgumentException("Access token TTL must be at least one second.");
        }
        if (refreshTokenTtl != null && (refreshTokenTtl.isNegative() || refreshTokenTtl.toSeconds() < 1)) {
            throw new IllegalArgumentException("Refresh token TTL must be at least one second.");
        }
    }
}
