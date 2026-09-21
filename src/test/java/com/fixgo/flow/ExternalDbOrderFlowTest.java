package com.fixgo.flow;

import com.fixgo.support.TestSupportConfig;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Local path without Docker: point TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD at a PostGIS database
 * (e.g. the Supabase dev project). Only the fixgo_test schema is touched; it is wiped on every run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSupportConfig.class)
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ExternalDbOrderFlowTest extends OrderFlowContract {
}
