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
    private static final CorsProperties WEB = new CorsProperties(java.util.List.of("https://app.fixgo.vn"));
    private static final CorsProperties DEV_CORS = new CorsProperties(java.util.List.of("http://localhost:*", "https://app.fixgo.vn"));

    @Test
    void productionRefusesToEchoOtpCodes() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(true, STRONG), SMS, CLOUD, WEB).verify())
                .hasMessageContaining("OTP_DEV_ECHO");
    }

    @Test
    void productionRefusesAShortJwtSecret() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(false, "short"), SMS, CLOUD, WEB).verify())
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void productionRefusesToRunWithoutARealSmsProvider() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(false, STRONG), LOG_ONLY, CLOUD, WEB).verify())
                .hasMessageContaining("SMS_PROVIDER");
    }

    @Test
    void productionRefusesToKeepUploadsOnTheServerDisk() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(false, STRONG), SMS, LOCAL, WEB).verify())
                .hasMessageContaining("STORAGE_PROVIDER");
    }

    @Test
    void productionRefusesDevelopmentCorsOrigins() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(false, STRONG), SMS, CLOUD, DEV_CORS).verify())
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
        assertThatThrownBy(() -> new ProductionSafetyCheck(prod(), auth(false, STRONG), SMS, CLOUD,
                new CorsProperties(java.util.List.of("*"))).verify()).hasMessageContaining("CORS_ALLOWED_ORIGINS");
    }

    @Test
    void productionStartsWithSafeSettings() {
        assertThatCode(() -> new ProductionSafetyCheck(prod(), auth(false, STRONG), SMS, CLOUD, WEB).verify()).doesNotThrowAnyException();
    }

    @Test
    void developerMachinesMayEchoCodes() {
        assertThatCode(() -> new ProductionSafetyCheck(new MockEnvironment(), auth(true, "dev"), LOG_ONLY, LOCAL, DEV_CORS).verify())
                .doesNotThrowAnyException();
    }
}
