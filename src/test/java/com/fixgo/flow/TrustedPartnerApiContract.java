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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/admin/partners/trusted: an admin adds a partner verified outside the system (no KYC files).
 * HT-04: anonymous, customer and partner callers are refused; the created partner can go online straight away.
 */
abstract class TrustedPartnerApiContract {
    /** Own phone prefix so these accounts never collide with the other contracts in a shared schema. */
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(4000);
    private static final String ADMIN_PHONE = "+84900000009";
    private static final String CREATE = "/api/v1/admin/partners/trusted";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void anonymousCustomersAndPartnersCannotAddTrustedPartners() throws Exception {
        postJson(CREATE, null, body("Tho A", nextPhone())).andExpect(status().isUnauthorized());
        String customer = login(nextPhone()).token;
        postJson(CREATE, customer, body("Tho B", nextPhone())).andExpect(status().isForbidden());
        // A partner made through this very endpoint is still refused: only admins may vouch for partners.
        String partnerPhone = nextPhone();
        postJson(CREATE, login(ADMIN_PHONE).token, body("Tho C", partnerPhone)).andExpect(status().isCreated());
        postJson(CREATE, login(partnerPhone).token, body("Tho D", nextPhone())).andExpect(status().isForbidden());
    }

    @Test
    void trustedPartnerIsApprovedWithoutDocumentsAndCanGoOnline() throws Exception {
        String admin = login(ADMIN_PHONE).token;
        String phone = nextPhone();
        var created = read(postJson(CREATE, admin, Map.of("fullName", "Nguyen Tho Quen", "phone", phone,
                "serviceCodes", List.of("tire-patch", "tire-pump"), "avatarUrl", "https://example.com/a.jpg"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.path("verificationStatus").asText()).isEqualTo("APPROVED");
        assertThat(created.path("accountCreated").asBoolean()).isTrue();
        assertThat(created.path("serviceCodes").toString()).isEqualTo("[\"tire-patch\",\"tire-pump\"]");

        var partner = login(phone);
        assertThat(partner.userId).isEqualTo(created.path("userId").asText());
        var me = read(mvc.perform(get("/api/v1/partner/me").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(me.path("fullName").asText()).isEqualTo("Nguyen Tho Quen");
        assertThat(me.path("verificationStatus").asText()).isEqualTo("APPROVED");
        assertThat(me.path("documents").size()).as("no KYC files are stored").isZero();
        mvc.perform(patch("/api/v1/partner/me/presence").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + partner.token)
                        .content(json.writeValueAsString(Map.of("availability", "ONLINE", "lat", 10.87, "lng", 106.80))))
                .andExpect(status().isOk());
        var user = read(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(user.path("avatarUrl").asText()).isEqualTo("https://example.com/a.jpg");
    }

    @Test
    void anExistingCustomerBecomesThePartnerButNoPhoneIsAddedTwice() throws Exception {
        String admin = login(ADMIN_PHONE).token;
        String phone = nextPhone();
        var customer = login(phone);
        var created = read(postJson(CREATE, admin, body("Tho Tung La Khach", phone)).andExpect(status().isCreated()).andReturn());
        assertThat(created.path("userId").asText()).isEqualTo(customer.userId);
        assertThat(created.path("accountCreated").asBoolean()).isFalse();
        postJson(CREATE, admin, body("Lan Hai", phone)).andExpect(status().isConflict());
        postJson(CREATE, admin, body("Admin", ADMIN_PHONE)).andExpect(status().isConflict());
    }

    @Test
    void unknownServicesAndInvalidPhonesAreRejected() throws Exception {
        String admin = login(ADMIN_PHONE).token;
        postJson(CREATE, admin, Map.of("fullName", "Tho X", "phone", nextPhone(), "serviceCodes", List.of("no-such-service")))
                .andExpect(status().isBadRequest());
        postJson(CREATE, admin, body("Tho Y", "08590145147")).andExpect(status().isBadRequest());
        postJson(CREATE, admin, Map.of("fullName", "Tho Z", "phone", nextPhone(), "serviceCodes", List.of()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    record Session(String token, String userId) { }

    Session login(String phone) throws Exception {
        var otp = read(postJson("/api/v1/auth/otp", null, Map.of("phone", phone)).andExpect(status().isOk()).andReturn());
        var tokens = read(postJson("/api/v1/auth/otp/verify", null, Map.of("otpId", otp.path("otpId").asText(),
                "code", otp.path("devCode").asText(), "platform", "ANDROID", "deviceFingerprint", "test-" + phone))
                .andExpect(status().isOk()).andReturn());
        return new Session(tokens.path("accessToken").asText(), tokens.path("user").path("id").asText());
    }

    static Map<String, Object> body(String fullName, String phone) {
        return Map.of("fullName", fullName, "phone", phone, "serviceCodes", List.of("tire-patch"));
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

    String nextPhone() { return "093" + String.format("%07d", PHONE_SEQ.incrementAndGet() + 9000000); }
}
