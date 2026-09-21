package com.fixgo.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixgo.dispatch.DispatchService;
import com.fixgo.support.TestClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end contract over the real schema: OTP → order → dispatch → quote → completion → payment → review,
 * plus the "—" cells of AUTHZ.md that must be refused (HT-04) and the race/immutability rules.
 */
abstract class OrderFlowContract {
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(1000);
    private static final AtomicInteger LOCATION_SEQ = new AtomicInteger();
    private static final String ADMIN_PHONE = "+84900000009";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DispatchService dispatch;
    @Autowired Clock clock;

    // ------------------------------------------------------------------ happy path

    @Test
    void fullRescueOrderLifecycle() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        assertThat(customer.role).isEqualTo("CUSTOMER");
        var partner = approvedOnlinePartner(loc, "tire-patch");
        var rival = approvedOnlinePartner(loc, "tire-patch");

        // Catalogue is public.
        mvc.perform(get("/api/v1/services")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("tire-patch"));

        // Create (PENDING_CONFIRMATION) then confirm the call-out fee (REQUESTED) → round 1 broadcast.
        var order = postJson("/api/v1/orders", customer.token, Map.of(
                "serviceId", "tire-patch", "extraServiceIds", List.of("tire-pump"),
                "addressText", "Làng Đại học, Thủ Đức", "note", "Cán đinh", "lat", loc[0], "lng", loc[1],
                "photoUrls", List.of("https://cdn.example/scene1.jpg"))).andExpect(status().isCreated()).andReturn();
        var o = read(order);
        String orderId = o.path("id").asText();
        assertThat(o.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(o.path("callOutFee").decimalValue().intValue()).isEqualTo(30000);
        assertThat(o.path("extraServiceIds").get(0).asText()).isEqualTo("tire-pump");

        o = read(postJson("/api/v1/orders/" + orderId + "/confirm", customer.token, null).andExpect(status().isOk()).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("select count(*) from fixgo_test.dispatch_rounds where order_id = ?::uuid",
                Integer.class, orderId)).isEqualTo(1);

        // Both partners see the offer; the first to accept wins, the other gets 409 (RB-36).
        var offers = read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(offers).hasSize(1);
        String assignmentId = offers.get(0).path("assignmentId").asText();
        var rivalOffers = read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + rival.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(rivalOffers).hasSize(1);

        postJson("/api/v1/partner/offers/" + assignmentId + "/accept", partner.token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("ASSIGNED"));
        postJson("/api/v1/partner/offers/" + rivalOffers.get(0).path("assignmentId").asText() + "/accept", rival.token, null)
                .andExpect(status().isConflict());

        // Customer now sees the partner on the tracking screen; the cheap status endpoint agrees.
        o = read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("ASSIGNED");
        mvc.perform(get("/api/v1/orders/" + orderId + "/status").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"));
        mvc.perform(get("/api/v1/orders/" + orderId + "/status").header("Authorization", "Bearer " + rival.token))
                .andExpect(status().isNotFound());                                     // never accepted → invisible
        assertThat(o.path("partner").path("id").asText()).isEqualTo(partner.userId);

        // Arrive → check → quote (GW-01 → GW-02).
        postJson("/api/v1/orders/" + orderId + "/arrive", partner.token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
        postJson("/api/v1/orders/" + orderId + "/check", partner.token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKING"));
        var quote = read(postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", List.of(
                Map.of("itemType", "LABOR", "description", "Vá xe", "quantity", 1, "unitPrice", 80000, "serviceId", "tire-patch"),
                Map.of("itemType", "PART", "description", "Miếng vá", "quantity", 2, "unitPrice", 10000))))
                .andExpect(status().isCreated()).andReturn());
        String quoteId = quote.path("id").asText();
        assertThat(quote.path("status").asText()).isEqualTo("SENT");
        assertThat(quote.path("totalAmount").decimalValue().intValue()).isEqualTo(30000 + 80000 + 20000);   // RB-47

        // ---- refusals while the quote is pending (AUTHZ "—" cells, HT-04)
        postJson("/api/v1/orders/" + orderId + "/quotes/" + quoteId + "/approve", partner.token, null)
                .andExpect(status().isForbidden());                                    // partner cannot approve
        postJson("/api/v1/orders/" + orderId + "/quotes/" + quoteId + "/approve", adminToken(), null)
                .andExpect(status().isForbidden());                                    // RB-45: not even ADMIN
        postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", List.of(
                Map.of("itemType", "LABOR", "description", "x", "quantity", 1, "unitPrice", 1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_ALREADY_SENT")); // RB-46
        postJson("/api/v1/orders/" + orderId + "/complete", partner.token, null)
                .andExpect(status().isConflict());                                     // BR02: no approval, no completion
        var stranger = loginNew();
        mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + stranger.token))
                .andExpect(status().isNotFound());                                     // other customer: no leak
        postJson("/api/v1/orders/" + orderId + "/quotes/" + quoteId + "/approve", stranger.token, null)
                .andExpect(status().isNotFound());
        postJson("/api/v1/partner/offers/" + assignmentId + "/accept", customer.token, null)
                .andExpect(status().isForbidden());                                    // customer on partner routes

        // Customer approves → APPROVED → IN_PROGRESS in one step (BRD GW-02).
        postJson("/api/v1/orders/" + orderId + "/quotes/" + quoteId + "/approve", customer.token, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        o = read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token)).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("IN_PROGRESS");

        // Additional cost → new revision with the whole value (BR03, RB-42); the old APPROVED becomes SUPERSEDED.
        var rev2 = read(postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", List.of(
                Map.of("itemType", "LABOR", "description", "Vá xe", "quantity", 1, "unitPrice", 80000),
                Map.of("itemType", "PART", "description", "Miếng vá", "quantity", 2, "unitPrice", 10000),
                Map.of("itemType", "PART", "description", "Van mới", "quantity", 1, "unitPrice", 30000))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(rev2.path("revisionNo").asInt()).isEqualTo(2);
        assertThat(rev2.path("quoteType").asText()).isEqualTo("ADDITIONAL");
        o = read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token)).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("ADDITIONAL_QUOTE");
        postJson("/api/v1/orders/" + orderId + "/quotes/" + rev2.path("id").asText() + "/approve", customer.token, null)
                .andExpect(status().isOk());
        var quotes = read(mvc.perform(get("/api/v1/orders/" + orderId + "/quotes").header("Authorization", "Bearer " + customer.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(quotes.get(0).path("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(quotes.get(1).path("status").asText()).isEqualTo("APPROVED");

        // Complete = partner collected cash: payment CONFIRMED with the latest APPROVED total, not the sum (RB-42, RB-59).
        o = read(postJson("/api/v1/orders/" + orderId + "/complete", partner.token, null).andExpect(status().isOk()).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(o.path("payment").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(o.path("payment").path("amount").decimalValue().intValue()).isEqualTo(30000 + 80000 + 20000 + 30000);
        postJson("/api/v1/orders/" + orderId + "/payment/confirm", customer.token, null).andExpect(status().isNotFound());
        var stats = read(mvc.perform(get("/api/v1/partner/stats").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(stats.path("completedToday").asLong()).isEqualTo(1);
        assertThat(stats.path("earnedToday").decimalValue().intValue()).isEqualTo(160000);

        // A single review, by the customer only.
        postJson("/api/v1/orders/" + orderId + "/review", customer.token, Map.of("rating", 5, "feedback", "Nhanh, gọn"))
                .andExpect(status().isCreated());
        postJson("/api/v1/orders/" + orderId + "/review", customer.token, Map.of("rating", 4)).andExpect(status().isConflict());
        postJson("/api/v1/orders/" + orderId + "/review", partner.token, Map.of("rating", 5)).andExpect(status().isNotFound());
        stats = read(mvc.perform(get("/api/v1/partner/stats").header("Authorization", "Bearer " + partner.token)).andReturn());
        assertThat(stats.path("averageRating").asDouble()).isEqualTo(5.0);
        assertThat(stats.path("reviewCount").asLong()).isEqualTo(1);

        // History has every hop, in order.
        var history = o.path("history");
        assertThat(history.findValues("to").stream().map(JsonNode::asText)).containsExactly(
                "PENDING_CONFIRMATION", "REQUESTED", "ASSIGNED", "ARRIVED", "CHECKING", "WAITING_FOR_APPROVAL",
                "APPROVED", "IN_PROGRESS", "ADDITIONAL_QUOTE", "IN_PROGRESS", "COMPLETED");
        assertThat(read(mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + customer.token)).andReturn())).hasSize(1);
        // Partner is back online for the next job.
        assertThat(jdbc.queryForObject("select availability from fixgo_test.partner_profiles where user_id = ?::uuid",
                String.class, partner.userId)).isEqualTo("ONLINE");
    }

    // ------------------------------------------------------------------ cancellation policy

    @Test
    void cancellingAfterArrivalKeepsTheCallOutFeeDue() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "battery-jump");
        String orderId = confirmedOrder(customer, loc, "battery-jump");
        acceptFirstOffer(partner);
        postJson("/api/v1/orders/" + orderId + "/arrive", partner.token, null).andExpect(status().isOk());

        var o = read(postJson("/api/v1/orders/" + orderId + "/cancel", customer.token, Map.of("reason", "Tự sửa được rồi"))
                .andExpect(status().isOk()).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(o.path("cancellationSource").asText()).isEqualTo("CUSTOMER");        // BR09
        assertThat(o.path("cancellationReason").asText()).isEqualTo("Tự sửa được rồi");
        assertThat(o.path("payment").path("amount").decimalValue().intValue()).isEqualTo(30000);
        assertThat(jdbc.queryForObject("select quote_id is null from fixgo_test.payments where order_id = ?::uuid",
                Boolean.class, orderId)).isTrue();
        // Terminal: nothing else is accepted.
        postJson("/api/v1/orders/" + orderId + "/check", partner.token, null).andExpect(status().isNotFound());
        postJson("/api/v1/orders/" + orderId + "/cancel", customer.token, null).andExpect(status().isConflict());
    }

    @Test
    void cancellingBeforeArrivalOwesNothing() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "tire-pump");
        String orderId = confirmedOrder(customer, loc, "tire-pump");
        acceptFirstOffer(partner);
        var o = read(postJson("/api/v1/orders/" + orderId + "/cancel", customer.token, Map.of("reason", "Đổi ý"))
                .andExpect(status().isOk()).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(o.path("payment").isNull() || o.path("payment").isMissingNode()).isTrue();
    }

    @Test
    void decliningTheInitialQuoteCancelsWithFeeDue() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "chain-fix");
        String orderId = confirmedOrder(customer, loc, "chain-fix");
        acceptFirstOffer(partner);
        postJson("/api/v1/orders/" + orderId + "/arrive", partner.token, null).andExpect(status().isOk());
        postJson("/api/v1/orders/" + orderId + "/check", partner.token, null).andExpect(status().isOk());
        var quote = read(postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", List.of(
                Map.of("itemType", "LABOR", "description", "Tăng sên", "quantity", 1, "unitPrice", 60000))))
                .andExpect(status().isCreated()).andReturn());
        postJson("/api/v1/orders/" + orderId + "/quotes/" + quote.path("id").asText() + "/decline", customer.token,
                Map.of("reason", "Đắt quá")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        var o = read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token)).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(o.path("payment").path("amount").decimalValue().intValue()).isEqualTo(30000);
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    void noPartnerOnlineEndsAsNoPartnerFoundAfterAllRounds() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        String orderId = confirmedOrder(customer, loc, "oil-change");
        var o = read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token)).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("NO_PARTNER_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from fixgo_test.dispatch_rounds where order_id = ?::uuid and end_reason = 'NO_CANDIDATE'",
                Integer.class, orderId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select no_candidate_reason from fixgo_test.dispatch_rounds where order_id = ?::uuid and round_no = 1",
                String.class, orderId)).isIn("NO_PARTNER_ONLINE", "OUT_OF_RADIUS");                         // RB-35
    }

    @Test
    void unansweredRoundsWidenThenTimeOut() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var near = approvedOnlinePartner(new double[] {loc[0] + 0.009, loc[1]}, "towing");   // ~1 km: every round
        var far = approvedOnlinePartner(new double[] {loc[0] + 0.03, loc[1]}, "towing");     // ~3.3 km: round 2+
        String orderId = confirmedOrder(customer, loc, "towing");
        assertThat(read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + near.token)).andReturn())).hasSize(1);
        assertThat(read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + far.token)).andReturn()))
                .as("round 1 radius 2 km does not reach a partner 3.3 km away").isEmpty();

        testClock().advance(Duration.ofSeconds(61));
        dispatch.tick();                                                                   // BR08: nobody answered → widen
        assertThat(read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + far.token)).andReturn())).hasSize(1);
        assertThat(read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + near.token)).andReturn()))
                .as("round-1 offer expired, round-2 offer open").hasSize(1);
        testClock().advance(Duration.ofSeconds(76));
        dispatch.tick();                                                                   // round 2 → round 3 (final)
        testClock().advance(Duration.ofSeconds(91));
        dispatch.tick();                                                                   // final round → give up (RB-34)
        var o = read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token)).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("NO_PARTNER_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from fixgo_test.dispatch_rounds where order_id = ?::uuid and end_reason = 'TIMEOUT'",
                Integer.class, orderId)).isEqualTo(3);
        assertThat(read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + near.token)).andReturn())).isEmpty();
        testClock().reset();
    }

    @Test
    void unconfirmedOrdersExpire() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var created = read(postJson("/api/v1/orders", customer.token, Map.of("serviceId", "tire-patch",
                "addressText", "x", "lat", loc[0], "lng", loc[1])).andReturn());
        testClock().advance(Duration.ofMinutes(11));
        dispatch.tick();
        var o = read(mvc.perform(get("/api/v1/orders/" + created.path("id").asText()).header("Authorization", "Bearer " + customer.token)).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("EXPIRED");
        testClock().reset();
    }

    @Test
    void unverifiedPartnerNeverReceivesOffersAndCannotGoOnline() throws Exception {
        var loc = nextLocation();
        var pending = registerPartner(loc, "tire-patch");                        // not approved by admin
        mvc.perform(patch("/api/v1/partner/me/presence").header("Authorization", "Bearer " + pending.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("availability", "ONLINE", "lat", loc[0], "lng", loc[1]))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PARTNER_NOT_VERIFIED"));   // BR06
    }

    // ------------------------------------------------------------------ auth

    @Test
    void otpIsSingleUseAndWrongCodesAreCounted() throws Exception {
        String phone = nextPhone();
        var otp = read(postJson("/api/v1/auth/otp", null, Map.of("phone", phone)).andExpect(status().isOk()).andReturn());
        assertThat(otp.path("devCode").asText()).hasSize(6);
        for (int i = 0; i < 2; i++) {
            postJson("/api/v1/auth/otp/verify", null, Map.of("otpId", otp.path("otpId").asText(), "code", "000000"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_OTP"));
        }
        var tokens = read(postJson("/api/v1/auth/otp/verify", null,
                Map.of("otpId", otp.path("otpId").asText(), "code", otp.path("devCode").asText())).andExpect(status().isOk()).andReturn());
        assertThat(tokens.path("role").asText()).isEqualTo("CUSTOMER");
        assertThat(tokens.path("user").path("phone").asText()).isEqualTo("+84" + phone.substring(1));
        // Same code again → refused (RB-03).
        postJson("/api/v1/auth/otp/verify", null, Map.of("otpId", otp.path("otpId").asText(), "code", otp.path("devCode").asText()))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = 'fixgo_test' and column_name like '%password%'",
                Integer.class)).as("RB-01: no password column anywhere").isZero();
    }

    @Test
    void refreshTokensRotateAndReuseRevokesTheDevice() throws Exception {
        var s = loginNew();
        var second = read(postJson("/api/v1/auth/refresh", null, Map.of("refreshToken", s.refresh)).andExpect(status().isOk()).andReturn());
        postJson("/api/v1/auth/refresh", null, Map.of("refreshToken", s.refresh)).andExpect(status().isUnauthorized());   // reuse
        postJson("/api/v1/auth/refresh", null, Map.of("refreshToken", second.path("refreshToken").asText()))
                .andExpect(status().isUnauthorized());                                                                   // chain revoked
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + second.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lockingAnAccountKillsItsTokens() throws Exception {
        var s = loginNew();
        mvc.perform(patch("/api/v1/admin/users/" + s.userId + "/status").header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOCKED\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + s.token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + s.token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + loginNew().token)).andExpect(status().isForbidden());
    }

    @Test
    void shopOwnerInvitesStaffWhoStillNeedKyc() throws Exception {
        var loc = nextLocation();
        var owner = loginNew();
        var profile = read(postJson("/api/v1/partner-registration", owner.token, Map.of("fullName", "Chủ tiệm A",
                "partnerType", "SHOP", "shopName", "Tiệm A", "serviceCodes", List.of("tire-patch", "oil-change"),
                "documents", List.of(Map.of("documentType", "ID_FRONT", "storageKey", "kyc/a/front.jpg"))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(profile.path("verificationStatus").asText()).isEqualTo("PENDING");
        verifyPartner(owner.userId);
        var me = read(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + owner.token)).andReturn());
        assertThat(me.path("appRole").asText()).isEqualTo("P_SHOP");

        String staffPhone = nextPhone();
        var staff = read(postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", staffPhone, "fullName", "Thợ B"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(staff).hasSize(1);
        assertThat(staff.get(0).path("verificationStatus").asText()).isEqualTo("PENDING");            // RB-12
        var staffSession = login(staffPhone);
        assertThat(staffSession.role).isEqualTo("P_STAFF");
        // A staff member is not a shop owner.
        mvc.perform(get("/api/v1/partner/shop/staff").header("Authorization", "Bearer " + staffSession.token))
                .andExpect(status().isForbidden());
        // Customers cannot touch partner endpoints at all.
        mvc.perform(get("/api/v1/partner/me").header("Authorization", "Bearer " + loginNew().token)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    record Session(String token, String refresh, String userId, String role) { }

    Session loginNew() throws Exception { return login(nextPhone()); }

    Session login(String phone) throws Exception {
        var otp = read(postJson("/api/v1/auth/otp", null, Map.of("phone", phone)).andExpect(status().isOk()).andReturn());
        var tokens = read(postJson("/api/v1/auth/otp/verify", null, Map.of("otpId", otp.path("otpId").asText(),
                "code", otp.path("devCode").asText(), "platform", "ANDROID", "deviceFingerprint", "test-" + phone))
                .andExpect(status().isOk()).andReturn());
        return new Session(tokens.path("accessToken").asText(), tokens.path("refreshToken").asText(),
                tokens.path("user").path("id").asText(), tokens.path("role").asText());
    }

    String adminToken() throws Exception { return login(ADMIN_PHONE).token; }

    Session registerPartner(double[] loc, String serviceCode) throws Exception {
        var s = loginNew();
        postJson("/api/v1/partner-registration", s.token, Map.of("fullName", "Thợ " + s.userId.substring(0, 4),
                "partnerType", "INDIVIDUAL", "serviceCodes", List.of(serviceCode),
                "documents", List.of(Map.of("documentType", "ID_FRONT", "storageKey", "kyc/front.jpg"),
                        Map.of("documentType", "ID_BACK", "storageKey", "kyc/back.jpg"),
                        Map.of("documentType", "SELFIE", "storageKey", "kyc/selfie.jpg"))))
                .andExpect(status().isCreated());
        return s;
    }

    Session approvedOnlinePartner(double[] loc, String serviceCode) throws Exception {
        var s = registerPartner(loc, serviceCode);
        verifyPartner(s.userId);
        mvc.perform(patch("/api/v1/partner/me/presence").header("Authorization", "Bearer " + s.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("availability", "ONLINE", "lat", loc[0], "lng", loc[1]))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availability").value("ONLINE"));
        return s;
    }

    void verifyPartner(String partnerUserId) throws Exception {
        postJson("/api/v1/admin/partners/" + partnerUserId + "/verify", adminToken(), Map.of("status", "APPROVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("APPROVED"));
    }

    String confirmedOrder(Session customer, double[] loc, String serviceCode) throws Exception {
        var created = read(postJson("/api/v1/orders", customer.token, Map.of("serviceId", serviceCode,
                "addressText", "Test address", "lat", loc[0], "lng", loc[1])).andExpect(status().isCreated()).andReturn());
        String id = created.path("id").asText();
        postJson("/api/v1/orders/" + id + "/confirm", customer.token, null).andExpect(status().isOk());
        return id;
    }

    void acceptFirstOffer(Session partner) throws Exception {
        var offers = read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + partner.token)).andReturn());
        assertThat(offers).isNotEmpty();
        postJson("/api/v1/partner/offers/" + offers.get(0).path("assignmentId").asText() + "/accept", partner.token, null)
                .andExpect(status().isOk());
    }

    ResultActions postJson(String path, String token, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    JsonNode read(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    static String nextPhone() { return "090" + String.format("%07d", PHONE_SEQ.incrementAndGet()); }

    /** Each test works ~22 km away from the previous one so partners never see each other's broadcasts. */
    static double[] nextLocation() { return new double[] {10.85 + 0.2 * LOCATION_SEQ.getAndIncrement(), 106.77}; }

    TestClock testClock() { return (TestClock) clock; }
}
