package com.fixgo.flow;

import com.fixgo.support.TestSupportConfig;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Local path without Docker — same setup as {@link ExternalDbOrderFlowTest}: needs TEST_DB_URL and friends. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSupportConfig.class)
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ExternalDbAdminApiTest extends AdminApiContract {
}
