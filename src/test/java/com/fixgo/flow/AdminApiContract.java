package com.fixgo.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-only admin dashboard endpoints (overview, partner/KYC queue, order list). HT-04: every role that must
 * NOT reach them is refused, and the ADMIN responses have the shape the web page relies on.
 * Kept separate from {@link OrderFlowContract} so the two never edit each other.
 */
abstract class AdminApiContract {
    /** A different prefix from OrderFlowContract's phones, so accounts never collide in a shared schema. */
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(2000);
    private static final String ADMIN_PHONE = "+84900000009";
    private static final List<String> DASHBOARD_ENDPOINTS =
            List.of("/api/v1/admin/overview", "/api/v1/admin/partners", "/api/v1/admin/orders");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    // ------------------------------------------------------------------ who may call it (HT-04)

    @Test
    void everyDashboardEndpointRefusesAnonymousCallers() throws Exception {
        for (String path : DASHBOARD_ENDPOINTS) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void customersAndPartnersAreForbiddenFromEveryDashboardEndpoint() throws Exception {
        var customer = loginNew();
        var partner = registerPartner();
        for (String path : DASHBOARD_ENDPOINTS) {
            mvc.perform(get(path).header("Authorization", "Bearer " + customer))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", "Bearer " + partner.token))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ what the admin gets

    @Test
    void overviewCountsAllFourteenOrderStatesAndReadsTheConfiguredCommission() throws Exception {
        var o = read(mvc.perform(get("/api/v1/admin/overview").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk()).andReturn());
        assertThat(o.path("orders").path("byStatus").size()).as("all 14 states are always present").isEqualTo(14);
        var users = o.path("users");
        assertThat(users.path("admins").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(users.path("total").asLong()).isEqualTo(
                users.path("customers").asLong() + users.path("partners").asLong() + users.path("admins").asLong());
        assertThat(o.path("partners").has("pending")).isTrue();
        assertThat(o.path("revenue").path("confirmedTotal").isNumber()).isTrue();
        // RB-23: the rate comes from commission_configs (seeded 10% in V2), not from a constant in code.
        assertThat(o.path("revenue").path("commissionRate").decimalValue()).isEqualByComparingTo("0.1");
    }

    @Test
    void kycQueueShowsNewApplicantsWithTheirDocumentsUntilAnAdminDecides() throws Exception {
        String admin = adminToken();
        var partner = registerPartner();

        var row = find(list("/api/v1/admin/partners?status=PENDING&size=100", admin), "userId", partner.userId);
        assertThat(row).as("fresh applicant is in the PENDING queue").isNotNull();
        assertThat(row.path("verificationStatus").asText()).isEqualTo("PENDING");
        assertThat(row.path("phone").asText()).isNotBlank();
        assertThat(row.path("documents")).hasSize(3);

        mvc.perform(post("/api/v1/admin/partners/" + partner.userId + "/verify")
                        .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        assertThat(find(list("/api/v1/admin/partners?status=PENDING&size=100", admin), "userId", partner.userId))
                .as("approved partner leaves the queue").isNull();
        var approved = find(list("/api/v1/admin/partners?status=APPROVED&size=100", admin), "userId", partner.userId);
        assertThat(approved).isNotNull();
        approved.path("documents").forEach(d -> assertThat(d.path("reviewStatus").asText()).isEqualTo("APPROVED"));
    }

    @Test
    void orderListFiltersByStatusAndShowsServiceCustomerAndFees() throws Exception {
        String admin = adminToken();
        var order = read(mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + loginNew())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("serviceId", "tire-patch",
                                "addressText", "Admin list test", "lat", 10.85, "lng", 106.77))))
                .andExpect(status().isCreated()).andReturn());
        String id = order.path("id").asText();

        var row = find(list("/api/v1/admin/orders?status=PENDING_CONFIRMATION&size=100", admin), "id", id);
        assertThat(row).as("unconfirmed order is listed under its status").isNotNull();
        assertThat(row.path("orderCode").asText()).isEqualTo(order.path("orderCode").asText());
        assertThat(row.path("serviceName").asText()).isNotBlank();
        assertThat(row.path("customerPhone").asText()).isNotBlank();
        assertThat(row.path("callOutFee").decimalValue()).isEqualByComparingTo("30000");

        assertThat(find(list("/api/v1/admin/orders?status=COMPLETED&size=100", admin), "id", id))
                .as("filter excludes other states").isNull();
        assertThat(find(list("/api/v1/admin/orders?size=100", admin), "id", id))
                .as("no filter lists every state").isNotNull();
    }

    @Test
    void malformedFiltersAndPagingAreRejectedWith400() throws Exception {
        String admin = adminToken();
        for (String path : List.of("/api/v1/admin/orders?status=NOPE", "/api/v1/admin/partners?status=NOPE",
                "/api/v1/admin/orders?size=101", "/api/v1/admin/orders?size=0", "/api/v1/admin/partners?page=-1")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------ helpers

    record Session(String token, String userId) { }

    String loginNew() throws Exception { return login(nextPhone()).token; }

    Session login(String phone) throws Exception {
        var otp = read(postJson("/api/v1/auth/otp", null, Map.of("phone", phone)).andExpect(status().isOk()).andReturn());
        var tokens = read(postJson("/api/v1/auth/otp/verify", null, Map.of("otpId", otp.path("otpId").asText(),
                "code", otp.path("devCode").asText(), "platform", "ANDROID", "deviceFingerprint", "test-" + phone))
                .andExpect(status().isOk()).andReturn());
        return new Session(tokens.path("accessToken").asText(), tokens.path("user").path("id").asText());
    }

    String adminToken() throws Exception { return login(ADMIN_PHONE).token; }

    /** A phone that registered as a partner but is still PENDING KYC (3 documents uploaded). */
    Session registerPartner() throws Exception {
        var s = login(nextPhone());
        postJson("/api/v1/partner-registration", s.token, Map.of("fullName", "Tho " + s.userId.substring(0, 4),
                "partnerType", "INDIVIDUAL", "serviceCodes", List.of("tire-patch"),
                "documents", List.of(Map.of("documentType", "ID_FRONT", "storageKey", "kyc/front.jpg"),
                        Map.of("documentType", "ID_BACK", "storageKey", "kyc/back.jpg"),
                        Map.of("documentType", "SELFIE", "storageKey", "kyc/selfie.jpg"))))
                .andExpect(status().isCreated());
        return s;
    }

    JsonNode list(String path, String token) throws Exception {
        return read(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
    }

    /** The row of a page whose {@code field} equals {@code value}, or null. */
    static JsonNode find(JsonNode page, String field, String value) {
        for (JsonNode item : page.path("items")) {
            if (value.equals(item.path(field).asText())) return item;
        }
        return null;
    }

    ResultActions postJson(String path, String token, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    static String nextPhone() { return "091" + String.format("%07d", PHONE_SEQ.incrementAndGet()); }
}
