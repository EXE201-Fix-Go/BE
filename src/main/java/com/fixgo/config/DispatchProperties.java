package com.fixgo.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("fixgo.dispatch")
public record DispatchProperties(@NotNull Duration pendingConfirmationTtl, boolean schedulerEnabled,
                                 long schedulerIntervalMs) { }
