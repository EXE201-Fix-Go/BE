package com.fixgo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SupabaseProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "spring.profiles.active=supabase",
                    "DB_URL=jdbc:postgresql://example.invalid:5432/postgres?sslmode=require",
                    "DB_USERNAME=profile-test-user",
                    "DB_PASSWORD=profile-test-password",
                    "JWT_SECRET=profile-test-secret-not-used-for-signing");

    @Test
    void usesExplicitPostgresCredentialsWithoutStartingAConnection() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://example.invalid:5432/postgres?sslmode=require");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("profile-test-user");
            assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
        });
    }

    @Test
    void allDatabaseComponentsUseThePrivateSchemaAndPostgisSearchPath() {
        runner.run(context -> {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.hikari.connection-init-sql"))
                    .isEqualTo("SET search_path TO fixgo, extensions, public");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("fixgo");
            assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("fixgo");
            assertThat(env.getProperty("spring.flyway.default-schema")).isEqualTo("fixgo");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
        });
    }

    @Test
    void secureDefaults() {
        runner.run(context -> {
            var env = context.getEnvironment();
            assertThat(env.getProperty("fixgo.auth.otp-dev-echo", Boolean.class)).isFalse();
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(5);
        });
    }
}
