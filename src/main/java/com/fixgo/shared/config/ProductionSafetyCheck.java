package com.fixgo.shared.config;

import com.fixgo.shared.storage.StorageProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Refuses to start in the "prod" profile with settings that are only acceptable on a developer machine. */
@Component
public class ProductionSafetyCheck {
    private final Environment environment;
    private final AuthProperties auth;
    private final SmsProperties sms;
    private final StorageProperties storage;

    public ProductionSafetyCheck(Environment environment, AuthProperties auth, SmsProperties sms, StorageProperties storage) {
        this.environment = environment;
        this.auth = auth;
        this.sms = sms;
        this.storage = storage;
    }

    @PostConstruct
    void verify() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;
        if (auth.otpDevEcho()) {
            throw new IllegalStateException(
                    "OTP_DEV_ECHO must be false in production: it returns every login code in the API response.");
        }
        if (sms.isLogOnly()) {
            throw new IllegalStateException("SMS_PROVIDER must be set in production: with 'log' nobody receives a login code.");
        }
        if (storage.isLocal()) {
            throw new IllegalStateException("STORAGE_PROVIDER must not be 'local' in production: the server disk is wiped on every deploy, so photos and KYC documents would be lost.");
        }
        if (auth.jwtSecret() == null || auth.jwtSecret().length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters in production.");
        }
    }
}
