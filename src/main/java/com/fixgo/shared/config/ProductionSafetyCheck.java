package com.fixgo.shared.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Refuses to start in the "prod" profile with settings that are only acceptable on a developer machine. */
@Component
public class ProductionSafetyCheck {
    private final Environment environment;
    private final AuthProperties auth;

    public ProductionSafetyCheck(Environment environment, AuthProperties auth) {
        this.environment = environment;
        this.auth = auth;
    }

    @PostConstruct
    void verify() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;
        if (auth.otpDevEcho()) {
            throw new IllegalStateException(
                    "OTP_DEV_ECHO must be false in production: it returns every login code in the API response.");
        }
        if (auth.jwtSecret() == null || auth.jwtSecret().length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters in production.");
        }
    }
}
