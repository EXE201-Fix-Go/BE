package com.fixgo.shared.config;

import com.fixgo.shared.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyCheckTest {
    private static AuthProperties auth(boolean devEcho, String secret) {
        return new AuthProperties("fixgo", "fixgo-api", secret, Duration.ofMinutes(15), Duration.ofDays(30),
                Duration.ofMinutes(5), 5, 5, 30, devEcho, 0);
    }

    private static MockEnvironment prod() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private static final String STRONG = "0123456789abcdef0123456789abcdef";
    private static final SmsProperties SMS = new SmsProperties("esms", null);
    private static final SmsProperties LOG_ONLY = new SmsProperties("log", null);
    private static final StorageProperties CLOUD = new StorageProperties("supabase", null, null);
    private static final StorageProperties LOCAL = new StorageProperties("local", null, null);

    @Test
    void productionRefusesToEchoOtpCodes() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(true, STRONG), SMS, CLOUD).verify())
                .hasMessageContaining("OTP_DEV_ECHO");
    }

    @Test
    void productionRefusesAShortJwtSecret() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(false, "short"), SMS, CLOUD).verify())
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void productionRefusesToRunWithoutARealSmsProvider() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(false, STRONG), LOG_ONLY, CLOUD).verify())
                .hasMessageContaining("SMS_PROVIDER");
    }

    @Test
    void productionRefusesToKeepUploadsOnTheServerDisk() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(false, STRONG), SMS, LOCAL).verify())
                .hasMessageContaining("STORAGE_PROVIDER");
    }

    @Test
    void productionStartsWithSafeSettings() {
        assertThatCode(() -> new ProductionSafetyCheck(prod(), auth(false, STRONG), SMS, CLOUD).verify()).doesNotThrowAnyException();
    }

    @Test
    void developerMachinesMayEchoCodes() {
        assertThatCode(() -> new ProductionSafetyCheck(new MockEnvironment(), auth(true, "dev"), LOG_ONLY, LOCAL).verify())
                .doesNotThrowAnyException();
    }
}
