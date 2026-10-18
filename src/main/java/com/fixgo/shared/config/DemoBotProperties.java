package com.fixgo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Demo Bot Mechanic ("Thợ Bot Tự Động") used during standalone APK testing.
 */
@ConfigurationProperties("fixgo.demo-bot")
public record DemoBotProperties(Boolean enabled, Integer delaySeconds) {
    public boolean isEnabledByDefault() {
        return enabled == null || enabled;
    }

    public int defaultDelaySeconds() {
        return delaySeconds == null || delaySeconds <= 0 ? 20 : delaySeconds;
    }
}
