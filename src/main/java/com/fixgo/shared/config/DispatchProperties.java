package com.fixgo.shared.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("fixgo.dispatch")
public record DispatchProperties(@NotNull Duration pendingConfirmationTtl, boolean schedulerEnabled,
                                 long schedulerIntervalMs, Duration locationMaxAge) {
    /** Partners whose last position is older than this are not offered orders. Null or zero = no limit. */
    public boolean hasLocationLimit() { return locationMaxAge != null && !locationMaxAge.isZero() && !locationMaxAge.isNegative(); }
}
