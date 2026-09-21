package com.fixgo.support;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.time.Clock;

@TestConfiguration
public class TestSupportConfig {
    @Bean
    @Primary
    Clock testClock() { return new TestClock(); }

    /** Start every test context from an empty fixgo_test schema. */
    @Bean
    FlywayMigrationStrategy cleanMigrate() {
        return flyway -> { flyway.clean(); flyway.migrate(); };
    }
}
