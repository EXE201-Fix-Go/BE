package com.fixgo.order;

import com.fixgo.support.TestSupportConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL query/migration contracts. Runs against an isolated container, never the application DB. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSupportConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@WithMockUser(username = "00000000-0000-0000-0000-000000000001", roles = "CUSTOMER")
class CustomerOrderReadsContainerTest {
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private UUID order(UUID owner, String status, int ageSeconds) {
        jdbc.update("insert into app_users(id, role, status, created_at) values (?, 'CUSTOMER', 'ACTIVE', now()) on conflict do nothing", owner);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into rescue_orders(id, order_code, customer_id, requested_service_id, contact_phone,
                    pickup_lat, pickup_lng, pickup_location, pickup_address_text, call_out_fee_snapshot,
                    call_out_fee_config_id, status, created_at, version)
                select ?, ?, ?, s.id, '+84900000001', 10.87, 106.8,
                    public.ST_SetSRID(public.ST_MakePoint(106.8, 10.87), 4326)::public.geography,
                    'Read test', 30000, f.id, ?, ?, 0
                from services s cross join call_out_fee_configs f order by s.code, f.effective_from limit 1
                """, id, "READ-" + id.toString().substring(0, 8), owner, status,
                Timestamp.from(Instant.now().minusSeconds(ageSeconds)));
        return id;
    }

    @Test void historyPagesAndFiltersOnlyTheAuthenticatedCustomersOrders() throws Exception {
        order(CUSTOMER, "COMPLETED", 3);
        order(CUSTOMER, "CANCELLED", 2);
        order(CUSTOMER, "COMPLETED", 1);
        order(UUID.randomUUID(), "COMPLETED", 0);
        mvc.perform(get("/api/v1/orders/history?size=2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/v1/orders/history?size=2&page=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/orders/history?status=CANCELLED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
    }

    @Test void activeLookupNeverReturnsAnEndedOrAnotherCustomersOrder() throws Exception {
        UUID active = order(CUSTOMER, "REQUESTED", 1);
        order(UUID.randomUUID(), "ASSIGNED", 0);
        mvc.perform(get("/api/v1/orders/active"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(active.toString()));
        jdbc.update("update rescue_orders set status = 'COMPLETED' where id = ?", active);
        mvc.perform(get("/api/v1/orders/active")).andExpect(status().isNoContent());
    }

    @Test void historyUsesLatestPaymentWhenThereIsNoApprovedLatestQuote() throws Exception {
        UUID id = order(CUSTOMER, "CANCELLED", 0);
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                    insert into payments(id, order_id, method, fund_holder, amount, status, idempotency_key, created_at)
                    values (?, ?, 'CASH', 'PLATFORM', ?, 'PENDING', ?, ?)
                    """, UUID.randomUUID(), id, 45000 + i * 5000, UUID.randomUUID().toString(),
                    Timestamp.from(Instant.now().minusSeconds(10 - i)));
        }
        mvc.perform(get("/api/v1/orders/history"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].totalAmount").value(50000));
        mvc.perform(get("/api/v1/orders/history?size=10000")).andExpect(status().isBadRequest());
    }
}
