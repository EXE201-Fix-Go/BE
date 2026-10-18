package com.fixgo.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixgo.support.TestClock;
import org.junit.jupiter.api.Test;
import com.fixgo.module.dispatch.service.DispatchService;
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
        assertThat(activeJobs(partner)).as("one open job while the order is in progress").isEqualTo(1);
        o = read(postJson("/api/v1/orders/" + orderId + "/complete", partner.token, null).andExpect(status().isOk()).andReturn());
        assertThat(o.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(o.path("payment").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(o.path("payment").path("amount").decimalValue().intValue()).isEqualTo(30000 + 80000 + 20000 + 30000);
        postJson("/api/v1/orders/" + orderId + "/payment/confirm", customer.token, null).andExpect(status().isNotFound());
        var stats = read(mvc.perform(get("/api/v1/partner/stats").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(stats.path("completedToday").asLong()).isEqualTo(1);
        assertThat(stats.path("earnedToday").decimalValue().intValue()).isEqualTo(160000);
        assertThat(stats.path("activeJobs").asLong()).as("a completed order is no longer an active job").isZero();

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
    void pricingIsPublicAndComesFromTheConfiguredRates() throws Exception {
        mvc.perform(get("/api/v1/pricing")).andExpect(status().isOk())
                .andExpect(jsonPath("$.callOutFee").value(30000))
                .andExpect(jsonPath("$.travelPerKm").value(5000))
                .andExpect(jsonPath("$.travelFreeKm").value(0));
    }

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

    @Test
    void travelFeeIsProRataNotAWholeKilometre() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(new double[] {loc[0] + 0.004, loc[1]}, "tire-patch");   // ~450 m away
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(partner);
        var o = read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token)).andReturn());
        assertThat(o.path("travelDistanceKm").decimalValue()).isEqualByComparingTo("0.4");
        assertThat(o.path("travelFee").decimalValue().intValue())
                .as("0.4 km x 5,000/km = 2,000, not a flat 5,000 for the first kilometre").isEqualTo(2000);
    }

    // ------------------------------------------------------------------ S1 / S12 / NT-02

    @Test
    void contactDetailsAreHiddenOnceTheOrderEnds() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "tire-patch");
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(partner);

        // While the order is open each side sees the other's contact details (S1).
        var forCustomer = getOrder(customer, orderId);
        assertThat(forCustomer.path("partner").path("phone").asText()).isNotBlank();
        assertThat(forCustomer.path("partner").path("lat").isNull()).isFalse();
        var forPartner = getOrder(partner, orderId);
        assertThat(forPartner.path("contactPhone").asText()).isNotBlank();
        assertThat(forPartner.path("lat").isNull()).isFalse();

        driveToCompletion(orderId, customer, partner);

        // Completed: phone numbers and positions stop being shared; the partner's name stays for the invoice/review.
        forCustomer = getOrder(customer, orderId);
        assertThat(forCustomer.path("partner").path("fullName").asText()).isNotBlank();
        assertThat(forCustomer.path("partner").path("phone").isNull()).isTrue();
        assertThat(forCustomer.path("partner").path("lat").isNull()).isTrue();
        assertThat(forCustomer.path("partner").path("lng").isNull()).isTrue();
        forPartner = getOrder(partner, orderId);
        assertThat(forPartner.path("contactPhone").isNull()).isTrue();
        assertThat(forPartner.path("lat").isNull()).isTrue();
        // The customer's own list is redacted the same way.
        var list = read(mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + customer.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(list.get(0).path("partner").path("phone").isNull()).isTrue();
        // ADMIN keeps full visibility (AUTHZ S1).
        var admin = login(ADMIN_PHONE);
        assertThat(getOrder(admin, orderId).path("contactPhone").asText()).isNotBlank();
    }

    @Test
    void anExpiredQuoteCannotBeDecidedAndThePartnerMaySendAFreshOne() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "tire-patch");
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(partner);
        postJson("/api/v1/orders/" + orderId + "/arrive", partner.token, null).andExpect(status().isOk());
        postJson("/api/v1/orders/" + orderId + "/check", partner.token, null).andExpect(status().isOk());
        var items = List.of(Map.of("itemType", "LABOR", "description", "Vá xe", "quantity", 1, "unitPrice", 80000));
        var first = read(postJson("/api/v1/orders/" + orderId + "/quotes", partner.token,
                Map.of("items", items, "validMinutes", 5)).andExpect(status().isCreated()).andReturn());
        String firstId = first.path("id").asText();

        testClock().advance(Duration.ofMinutes(6));
        postJson("/api/v1/orders/" + orderId + "/quotes/" + firstId + "/approve", customer.token, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
        postJson("/api/v1/orders/" + orderId + "/quotes/" + firstId + "/decline", customer.token, Map.of("reason", "x"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));

        // The expired quote does not block a replacement (RB-46) and the order stays in WAITING_FOR_APPROVAL.
        var second = read(postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", items))
                .andExpect(status().isCreated()).andReturn());
        assertThat(second.path("revisionNo").asInt()).isEqualTo(2);
        assertThat(orderStatus(customer, orderId)).isEqualTo("WAITING_FOR_APPROVAL");
        postJson("/api/v1/orders/" + orderId + "/quotes/" + second.path("id").asText() + "/approve", customer.token, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(orderStatus(customer, orderId)).isEqualTo("IN_PROGRESS");
        testClock().reset();
    }

    @Test
    void onlyPeopleOnTheOrderCanConfirmPaymentOrReview() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "tire-patch");
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(partner);
        var stranger = loginNew();
        var otherPartner = approvedOnlinePartner(loc, "tire-patch");
        for (var outsider : List.of(stranger, otherPartner)) {
            postJson("/api/v1/orders/" + orderId + "/payment/confirm", outsider.token, null)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
            postJson("/api/v1/orders/" + orderId + "/review", outsider.token, Map.of("rating", 5))
                    .andExpect(status().isNotFound());
        }
        // The partner is on the order but may not review it; the customer may not review before completion.
        postJson("/api/v1/orders/" + orderId + "/review", partner.token, Map.of("rating", 5)).andExpect(status().isNotFound());
        postJson("/api/v1/orders/" + orderId + "/review", customer.token, Map.of("rating", 5))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_COMPLETED"));
    }

    // ------------------------------------------------------------------ partner withdraws / admin lock

    @Test
    void aPartnerWhoWithdrawsBeforeArrivingPutsTheOrderBackOut() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var near = approvedOnlinePartner(new double[] {loc[0] + 0.004, loc[1]}, "tire-patch");   // ~0.4 km
        var other = approvedOnlinePartner(new double[] {loc[0] + 0.009, loc[1]}, "tire-patch");  // ~1.0 km
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptOfferFor(near, orderId);
        assertThat(getOrder(customer, orderId).path("travelFee").decimalValue().intValue()).isEqualTo(2000);

        postJson("/api/v1/orders/" + orderId + "/withdraw", near.token, Map.of("reason", "Xe hỏng"))
                .andExpect(status().isNoContent());

        // The customer's request survives, with no partner and no stale travel fee.
        var after = getOrder(customer, orderId);
        assertThat(after.path("status").asText()).isEqualTo("REQUESTED");
        assertThat(after.path("partner").isNull()).isTrue();
        assertThat(after.path("travelFee").isNull()).isTrue();
        assertThat(availabilityOf(near)).isEqualTo("ONLINE");
        // The partner who left loses all access (S1) and is never offered the order again.
        mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + near.token)).andExpect(status().isNotFound());
        assertThat(read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + near.token)).andReturn())).isEmpty();
        // Somebody else gets a fresh round and the travel fee is recomputed for them.
        assertThat(jdbc.queryForObject("select count(*) from fixgo_test.dispatch_rounds where order_id = ?::uuid", Integer.class, orderId))
                .isEqualTo(2);
        acceptOfferFor(other, orderId);
        after = getOrder(customer, orderId);
        assertThat(after.path("status").asText()).isEqualTo("ASSIGNED");
        assertThat(after.path("partner").path("id").asText()).isEqualTo(other.userId);
        assertThat(after.path("travelFee").decimalValue().intValue()).isEqualTo(5000);
        // The history shows what happened.
        assertThat(after.path("history").findValuesAsText("note")).contains("Partner withdrew");
    }

    @Test
    void withdrawingWithNobodyElseAroundEndsTheOrderAsNoPartnerFound() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var only = approvedOnlinePartner(loc, "tire-patch");
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(only);
        // The partner app's "cancel" in ASSIGNED is a withdrawal, not a cancellation of the customer's order.
        postJson("/api/v1/orders/" + orderId + "/cancel", only.token, Map.of("reason", "Không đi được"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contactPhone").doesNotExist());
        assertThat(orderStatus(customer, orderId)).isEqualTo("NO_PARTNER_FOUND");
        assertThat(read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + only.token)).andReturn())).isEmpty();
    }

    @Test
    void withdrawingIsOnlyPossibleBeforeArrival() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "tire-patch");
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(partner);
        postJson("/api/v1/orders/" + orderId + "/withdraw", customer.token, null).andExpect(status().isForbidden());
        postJson("/api/v1/orders/" + orderId + "/arrive", partner.token, null).andExpect(status().isOk());
        postJson("/api/v1/orders/" + orderId + "/withdraw", partner.token, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(orderStatus(customer, orderId)).isEqualTo("ARRIVED");
    }

    @Test
    void anAdminCannotLockAPartnerWhoIsInTheMiddleOfAnOrder() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "tire-patch");
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(partner);
        String admin = adminToken();
        mvc.perform(patch("/api/v1/admin/users/" + partner.userId + "/status").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARTNER_HAS_ACTIVE_JOB"));
        // Once the open order is cancelled by the admin the lock goes through.
        postJson("/api/v1/orders/" + orderId + "/cancel", admin, Map.of("reason", "Admin xử lý")).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/users/" + partner.userId + "/status").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOCKED\"}")).andExpect(status().isOk());
    }

    @Test
    void aLocationPingMovesThePartnerWithoutTouchingTheirAvailability() throws Exception {
        var loc = nextLocation();
        var partner = approvedOnlinePartner(loc, "tire-patch");
        var customer = loginNew();
        mvc.perform(put("/api/v1/partner/me/location").header("Authorization", "Bearer " + partner.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":" + (loc[0] + 0.001) + ",\"lng\":" + loc[1] + "}"))
                .andExpect(status().isNoContent());
        var me = read(mvc.perform(get("/api/v1/partner/me").header("Authorization", "Bearer " + partner.token)).andReturn());
        assertThat(me.path("lat").asDouble()).isEqualTo(loc[0] + 0.001);
        assertThat(me.path("locationUpdatedAt").isNull()).isFalse();
        assertThat(me.path("availability").asText()).isEqualTo("ONLINE");

        // While on a job the partner stays BUSY, however many pings arrive.
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(partner);
        mvc.perform(put("/api/v1/partner/me/location").header("Authorization", "Bearer " + partner.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":" + loc[0] + ",\"lng\":" + loc[1] + "}"))
                .andExpect(status().isNoContent());
        assertThat(availabilityOf(partner)).isEqualTo("BUSY");

        // Bad input and wrong roles are refused (HT-04).
        mvc.perform(put("/api/v1/partner/me/location").header("Authorization", "Bearer " + partner.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":123,\"lng\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/partner/me/location").header("Authorization", "Bearer " + partner.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":10.8}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/partner/me/location").header("Authorization", "Bearer " + customer.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":10.8,\"lng\":106.7}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/partner/me/location").contentType(MediaType.APPLICATION_JSON).content("{\"lat\":10.8,\"lng\":106.7}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shopStaffAndRejectedPartnersCanSendDocumentsForReview() throws Exception {
        // Shop staff join through an invitation and have no documents yet (RB-12: they need their own KYC).
        var owner = approvedShop();
        String phone = nextPhone();
        var staff = login(phone);
        postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", phone, "fullName", "Thợ mới")).andExpect(status().isCreated());
        String invitationId = read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + staff.token)).andReturn())
                .get(0).path("id").asText();
        postJson("/api/v1/invitations/" + invitationId + "/accept", staff.token, null).andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/v1/partner/me").header("Authorization", "Bearer " + staff.token)).andReturn())
                .path("documents")).isEmpty();
        // Without documents the admin cannot approve (BR06).
        postJson("/api/v1/admin/partners/" + staff.userId + "/verify", adminToken(), Map.of("status", "APPROVED"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KYC_DOCUMENTS_MISSING"));

        // Fake keys and incomplete sets are refused, real ones are accepted and the profile stays PENDING.
        mvc.perform(put("/api/v1/partner/me/documents").header("Authorization", "Bearer " + staff.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("documents", List.of(Map.of("documentType", "ID_FRONT", "storageKey", "kyc/front.jpg"),
                                Map.of("documentType", "ID_BACK", "storageKey", "kyc/back.jpg"), Map.of("documentType", "SELFIE", "storageKey", "kyc/s.jpg"))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));
        mvc.perform(put("/api/v1/partner/me/documents").header("Authorization", "Bearer " + staff.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("documents", kycDocuments(staff, "ID_FRONT", "ID_BACK")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KYC_DOCUMENTS_MISSING"));
        var sent = read(mvc.perform(put("/api/v1/partner/me/documents").header("Authorization", "Bearer " + staff.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("documents", kycDocuments(staff, "ID_FRONT", "ID_BACK", "SELFIE")))))
                .andExpect(status().isOk()).andReturn());
        assertThat(sent.path("verificationStatus").asText()).isEqualTo("PENDING");
        assertThat(sent.path("documents")).hasSize(3);
        verifyPartner(staff.userId);

        // A rejected partner sends new documents and goes back to the queue; an approved one cannot swap them.
        var rejected = loginNew();
        postJson("/api/v1/partner-registration", rejected.token, Map.of("fullName", "Thợ bị từ chối", "partnerType", "INDIVIDUAL",
                "serviceCodes", List.of("tire-patch"), "documents", kycDocuments(rejected, "ID_FRONT", "ID_BACK", "SELFIE")))
                .andExpect(status().isCreated());
        postJson("/api/v1/admin/partners/" + rejected.userId + "/verify", adminToken(), Map.of("status", "REJECTED")).andExpect(status().isOk());
        var again = read(mvc.perform(put("/api/v1/partner/me/documents").header("Authorization", "Bearer " + rejected.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("documents", kycDocuments(rejected, "ID_FRONT", "ID_BACK", "SELFIE")))))
                .andExpect(status().isOk()).andReturn());
        assertThat(again.path("verificationStatus").asText()).isEqualTo("PENDING");
        verifyPartner(rejected.userId);
        mvc.perform(put("/api/v1/partner/me/documents").header("Authorization", "Bearer " + rejected.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("documents", kycDocuments(rejected, "ID_FRONT", "ID_BACK", "SELFIE")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
        // Customers cannot use it (HT-04).
        mvc.perform(put("/api/v1/partner/me/documents").header("Authorization", "Bearer " + loginNew().token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documents\":[]}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ one job at a time

    @Test
    void aPartnerWorksOneOrderAtATime() throws Exception {
        var loc = nextLocation();
        var partner = approvedOnlinePartner(loc, "tire-patch");
        var first = loginNew();
        var second = loginNew();
        String firstOrder = confirmedOrder(first, loc, "tire-patch");
        String secondOrder = confirmedOrder(second, loc, "tire-patch");
        var offers = read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(offers).hasSize(2);
        String offerForFirst = offerIdFor(offers, firstOrder);
        String offerForSecond = offerIdFor(offers, secondOrder);

        postJson("/api/v1/partner/offers/" + offerForFirst + "/accept", partner.token, null).andExpect(status().isOk());
        // A second offer is refused while the first order is open, and that order stays up for grabs.
        postJson("/api/v1/partner/offers/" + offerForSecond + "/accept", partner.token, Map.of("lat", loc[0], "lng", loc[1]))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARTNER_BUSY"));
        assertThat(orderStatus(second, secondOrder)).isEqualTo("REQUESTED");
        assertThat(availabilityOf(partner)).isEqualTo("BUSY");

        // Neither ONLINE (a second job) nor OFFLINE (abandoning the customer) while the order is open.
        for (String wanted : List.of("ONLINE", "OFFLINE")) {
            presence(partner, wanted, loc).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PARTNER_HAS_ACTIVE_JOB"));
        }
        presence(partner, "BUSY", loc).andExpect(status().isOk());      // a location ping keeps working
        assertThat(availabilityOf(partner)).isEqualTo("BUSY");

        // Finishing the order frees the partner for the waiting offer.
        assertThat(activeJobs(partner)).isEqualTo(1);
        driveToCompletion(firstOrder, first, partner);
        assertThat(availabilityOf(partner)).isEqualTo("ONLINE");
        assertThat(activeJobs(partner)).as("finished order no longer counts").isZero();
        postJson("/api/v1/partner/offers/" + offerForSecond + "/accept", partner.token, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("ASSIGNED"));
        // …and cancelling the second order frees them again.
        postJson("/api/v1/orders/" + secondOrder + "/cancel", second.token, Map.of("reason", "Đổi ý")).andExpect(status().isOk());
        presence(partner, "OFFLINE", loc).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ quote revisions keep the travel fee

    @Test
    void everyQuoteRevisionCarriesTheTravelFeeAndClientsCannotSendIt() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(new double[] {loc[0] + 0.0135, loc[1]}, "tire-patch");   // ~1.5 km: inside round 1
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        acceptFirstOffer(partner);
        var accepted = read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token)).andReturn());
        int travel = accepted.path("travelFee").decimalValue().intValue();
        assertThat(travel).as("the partner is farther than the free distance").isPositive();
        postJson("/api/v1/orders/" + orderId + "/arrive", partner.token, null).andExpect(status().isOk());
        postJson("/api/v1/orders/" + orderId + "/check", partner.token, null).andExpect(status().isOk());

        // The travel fee is system-derived: a client-supplied TRAVEL line would be charged twice.
        postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", List.of(
                Map.of("itemType", "LABOR", "description", "Vá xe", "quantity", 1, "unitPrice", 80000),
                Map.of("itemType", "TRAVEL", "description", "Phí đi lại", "quantity", 1, "unitPrice", 99000))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TRAVEL_FEE_NOT_EDITABLE"));

        var rev1 = read(postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", List.of(
                Map.of("itemType", "LABOR", "description", "Vá xe", "quantity", 1, "unitPrice", 80000))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(rev1.path("travelAmount").decimalValue().intValue()).isEqualTo(travel);
        assertThat(rev1.path("totalAmount").decimalValue().intValue()).isEqualTo(30000 + travel + 80000);
        postJson("/api/v1/orders/" + orderId + "/quotes/" + rev1.path("id").asText() + "/approve", customer.token, null)
                .andExpect(status().isOk());

        // RB-42: the revision holds the WHOLE order value, so it must still include the travel fee.
        var rev2 = read(postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", List.of(
                Map.of("itemType", "LABOR", "description", "Vá xe", "quantity", 1, "unitPrice", 80000),
                Map.of("itemType", "PART", "description", "Van mới", "quantity", 1, "unitPrice", 50000))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(rev2.path("quoteType").asText()).isEqualTo("ADDITIONAL");
        assertThat(rev2.path("travelAmount").decimalValue().intValue()).isEqualTo(travel);
        assertThat(rev2.path("totalAmount").decimalValue().intValue()).isEqualTo(30000 + travel + 80000 + 50000);
        assertThat(rev2.path("items").findValues("itemType").stream().filter(t -> "TRAVEL".equals(t.asText())).count())
                .as("exactly one travel line").isEqualTo(1);
        postJson("/api/v1/orders/" + orderId + "/quotes/" + rev2.path("id").asText() + "/approve", customer.token, null)
                .andExpect(status().isOk());

        var done = read(postJson("/api/v1/orders/" + orderId + "/complete", partner.token, null)
                .andExpect(status().isOk()).andReturn());
        assertThat(done.path("payment").path("amount").decimalValue().intValue())
                .as("the customer pays the latest revision in full, travel included")
                .isEqualTo(30000 + travel + 80000 + 50000);
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

    // ------------------------------------------------------------------ KYC documents (BR06)

    @Test
    void kycDocumentsAreRealUploadsOfTheRegistrantAndOnlyAnAdminCanReadThem() throws Exception {
        var s = loginNew();
        var other = loginNew();
        var body = new java.util.HashMap<String, Object>(Map.of("fullName", "Thợ KYC", "partnerType", "INDIVIDUAL",
                "serviceCodes", List.of("tire-patch")));
        // A made-up key, or someone else's upload, is refused.
        body.put("documents", List.of(Map.of("documentType", "ID_FRONT", "storageKey", "kyc/front.jpg"),
                Map.of("documentType", "ID_BACK", "storageKey", "kyc/back.jpg"), Map.of("documentType", "SELFIE", "storageKey", "kyc/selfie.jpg")));
        postJson("/api/v1/partner-registration", s.token, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));
        body.put("documents", kycDocuments(other, "ID_FRONT", "ID_BACK", "SELFIE"));
        postJson("/api/v1/partner-registration", s.token, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));
        // All three document types are needed.
        body.put("documents", kycDocuments(s, "ID_FRONT", "ID_BACK"));
        postJson("/api/v1/partner-registration", s.token, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KYC_DOCUMENTS_MISSING"));
        // Only images are accepted, whatever the client says the file is; anonymous uploads are refused.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/uploads/kyc")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "id.jpg", "image/jpeg", "<html>".getBytes()))
                        .header("Authorization", "Bearer " + s.token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/uploads/kyc")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "id.jpg", "image/jpeg", JPEG)))
                .andExpect(status().isUnauthorized());

        body.put("documents", kycDocuments(s, "ID_FRONT", "ID_BACK", "SELFIE"));
        postJson("/api/v1/partner-registration", s.token, body).andExpect(status().isCreated());

        // Only an admin lists and reads the files; they are streamed by the backend, never linked.
        String admin = adminToken();
        var docs = read(mvc.perform(get("/api/v1/admin/partners/" + s.userId + "/documents").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(docs).hasSize(3);
        assertThat(docs.get(0).path("fileAvailable").asBoolean()).isTrue();
        String docId = docs.get(0).path("id").asText();
        var content = mvc.perform(get("/api/v1/admin/partners/" + s.userId + "/documents/" + docId + "/content")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(content).isEqualTo(JPEG);
        mvc.perform(get("/api/v1/admin/partners/" + other.userId + "/documents/" + docId + "/content").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());                                  // a document id under the wrong partner
        for (var outsider : List.of(s, other)) {                                    // the owner and everybody else: refused (HT-04)
            mvc.perform(get("/api/v1/admin/partners/" + s.userId + "/documents").header("Authorization", "Bearer " + outsider.token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/partners/" + s.userId + "/documents/" + docId + "/content").header("Authorization", "Bearer " + outsider.token))
                    .andExpect(status().isForbidden());
        }
        verifyPartner(s.userId);
    }

    // ------------------------------------------------------------------ uploads & dashboard

    @Test
    void scenePhotosUploadAndReachThePartner() throws Exception {
        var loc = nextLocation();
        var customer = loginNew();
        var partner = approvedOnlinePartner(loc, "tire-patch");
        var png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        var uploaded = read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/uploads")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "scene.png", "image/png", png))
                        .header("Authorization", "Bearer " + customer.token))
                .andExpect(status().isCreated()).andReturn());
        String url = uploaded.path("url").asText();
        assertThat(url).contains("/uploads/").endsWith(".png");
        mvc.perform(get(url.substring(url.indexOf("/uploads/")))).andExpect(status().isOk());   // served publicly
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/uploads")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "x.txt", "text/plain", "hi".getBytes())))
                .andExpect(status().isUnauthorized());                                       // anonymous cannot upload

        var created = read(postJson("/api/v1/orders", customer.token, Map.of("serviceId", "tire-patch",
                "addressText", "x", "lat", loc[0], "lng", loc[1], "photoUrls", List.of(url))).andReturn());
        postJson("/api/v1/orders/" + created.path("id").asText() + "/confirm", customer.token, null).andExpect(status().isOk());
        var dash = read(mvc.perform(get("/api/v1/partner/dashboard").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(dash.path("profile").path("availability").asText()).isEqualTo("ONLINE");
        assertThat(dash.path("offers")).hasSize(1);
        assertThat(dash.path("offers").get(0).path("photoUrls").get(0).asText()).isEqualTo(url);
        assertThat(dash.path("stats").path("completedToday").asLong()).isZero();
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
    void shopStaffJoinOnlyByAcceptingAnInvitationAndStillNeedKyc() throws Exception {
        var owner = loginNew();
        var profile = read(postJson("/api/v1/partner-registration", owner.token, Map.of("fullName", "Chủ tiệm A",
                "partnerType", "SHOP", "shopName", "Tiệm A", "serviceCodes", List.of("tire-patch", "oil-change"),
                "documents", kycDocuments(owner, "ID_FRONT", "ID_BACK", "SELFIE")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(profile.path("verificationStatus").asText()).isEqualTo("PENDING");
        String staffPhone = nextPhone();
        // BR06: a shop the admin has not approved cannot recruit.
        postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", staffPhone, "fullName", "Thợ B"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PARTNER_NOT_VERIFIED"));
        verifyPartner(owner.userId);
        var me = read(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + owner.token)).andReturn());
        assertThat(me.path("appRole").asText()).isEqualTo("P_SHOP");

        // Inviting only records the invitation: the invited person's account is untouched.
        var invitee = login(staffPhone);
        var staffList = read(postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", staffPhone, "fullName", "Thợ B"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(staffList).isEmpty();
        assertThat(invitee.role).isEqualTo("CUSTOMER");
        assertThat(read(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + invitee.token)).andReturn())
                .path("appRole").asText()).isEqualTo("CUSTOMER");
        postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", staffPhone, "fullName", "Thợ B"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITATION_PENDING"));
        var forShop = read(mvc.perform(get("/api/v1/partner/shop/invitations").header("Authorization", "Bearer " + owner.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(forShop).hasSize(1);
        assertThat(forShop.get(0).path("status").asText()).isEqualTo("PENDING");

        // Only the invited number sees and can answer it.
        var mine = read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + invitee.token))
                .andExpect(status().isOk()).andReturn());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("shopName").asText()).isEqualTo("Tiệm A");
        String invitationId = mine.get(0).path("id").asText();
        var stranger = loginNew();
        assertThat(read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + stranger.token)).andReturn())).isEmpty();
        postJson("/api/v1/invitations/" + invitationId + "/accept", stranger.token, null).andExpect(status().isNotFound());
        postJson("/api/v1/invitations/" + invitationId + "/accept", owner.token, null).andExpect(status().isNotFound());
        postJson("/api/v1/invitations/" + invitationId + "/accept", adminToken(), null).andExpect(status().isForbidden());

        // Accepting is the invitee's own act: the account becomes shop staff, still PENDING KYC (RB-12).
        var accepted = read(postJson("/api/v1/invitations/" + invitationId + "/accept", invitee.token, null)
                .andExpect(status().isOk()).andReturn());
        assertThat(accepted.path("partnerType").asText()).isEqualTo("SHOP_STAFF");
        assertThat(accepted.path("verificationStatus").asText()).isEqualTo("PENDING");
        assertThat(accepted.path("serviceCodes")).hasSize(2);                                  // inherits the shop's services
        assertThat(read(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + invitee.token)).andReturn())
                .path("appRole").asText()).isEqualTo("P_STAFF");
        var staff = read(mvc.perform(get("/api/v1/partner/shop/staff").header("Authorization", "Bearer " + owner.token)).andReturn());
        assertThat(staff).hasSize(1);
        // Answered once only; the same phone cannot be invited again while it belongs to a shop.
        postJson("/api/v1/invitations/" + invitationId + "/accept", invitee.token, null).andExpect(status().isConflict());
        postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", staffPhone, "fullName", "Thợ B"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PARTNER"));
        // A staff member is not a shop owner.
        mvc.perform(get("/api/v1/partner/shop/staff").header("Authorization", "Bearer " + invitee.token))
                .andExpect(status().isForbidden());
        // Customers cannot touch partner endpoints at all.
        mvc.perform(get("/api/v1/partner/me").header("Authorization", "Bearer " + loginNew().token)).andExpect(status().isForbidden());
    }

    @Test
    void anInvitationCanBeDeclinedOrCancelledAndNeverTouchesTheAccount() throws Exception {
        var owner = approvedShop();
        String declinePhone = nextPhone();
        String cancelPhone = nextPhone();
        var decliner = login(declinePhone);
        var cancelled = login(cancelPhone);
        postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", declinePhone, "fullName", "A")).andExpect(status().isCreated());
        postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", cancelPhone, "fullName", "B")).andExpect(status().isCreated());

        String declineId = read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + decliner.token)).andReturn())
                .get(0).path("id").asText();
        postJson("/api/v1/invitations/" + declineId + "/decline", decliner.token, null).andExpect(status().isNoContent());
        assertThat(read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + decliner.token)).andReturn())).isEmpty();
        postJson("/api/v1/invitations/" + declineId + "/accept", decliner.token, null).andExpect(status().isConflict());

        String cancelId = read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + cancelled.token)).andReturn())
                .get(0).path("id").asText();
        // Another shop's owner cannot cancel it; its own owner can.
        var other = approvedShop();
        mvc.perform(delete("/api/v1/partner/shop/invitations/" + cancelId).header("Authorization", "Bearer " + other.token))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/partner/shop/invitations/" + cancelId).header("Authorization", "Bearer " + owner.token))
                .andExpect(status().isNoContent());
        postJson("/api/v1/invitations/" + cancelId + "/accept", cancelled.token, null).andExpect(status().isConflict());
        for (var person : List.of(decliner, cancelled)) {
            assertThat(read(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + person.token)).andReturn())
                    .path("appRole").asText()).isEqualTo("CUSTOMER");
        }
    }

    @Test
    void aCustomerWithAnOpenOrderCannotBecomeStaffUntilItEnds() throws Exception {
        var loc = nextLocation();
        var owner = approvedShop();
        String phone = nextPhone();
        var customer = login(phone);
        // A partner in range keeps the order open (offered). With nobody around it would end at once as NO_PARTNER_FOUND.
        approvedOnlinePartner(loc, "tire-patch");
        String orderId = confirmedOrder(customer, loc, "tire-patch");
        postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", phone, "fullName", "Khách")).andExpect(status().isCreated());
        String id = read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + customer.token)).andReturn())
                .get(0).path("id").asText();
        postJson("/api/v1/invitations/" + id + "/accept", customer.token, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_HAS_ACTIVE_ORDER"));
        assertThat(read(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + customer.token)).andReturn())
                .path("appRole").asText()).isEqualTo("CUSTOMER");

        // Once the order has ended the same invitation can be accepted.
        postJson("/api/v1/orders/" + orderId + "/cancel", customer.token, Map.of("reason", "Đổi ý")).andExpect(status().isOk());
        postJson("/api/v1/invitations/" + id + "/accept", customer.token, null).andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + customer.token)).andReturn())
                .path("appRole").asText()).isEqualTo("P_STAFF");
    }

    @Test
    void staffCanLeaveAndTheOwnerCanRemoveThemWithoutLosingTheAccount() throws Exception {
        var owner = approvedShop();
        var rival = approvedShop();
        String phone = nextPhone();
        var staff = login(phone);
        postJson("/api/v1/partner/shop/staff", owner.token, Map.of("phone", phone, "fullName", "Thợ")).andExpect(status().isCreated());
        String id = read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + staff.token)).andReturn()).get(0).path("id").asText();
        postJson("/api/v1/invitations/" + id + "/accept", staff.token, null).andExpect(status().isOk());

        // Another shop cannot remove this person; the owner can.
        mvc.perform(delete("/api/v1/partner/shop/staff/" + staff.userId).header("Authorization", "Bearer " + rival.token))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/partner/shop/staff/" + staff.userId).header("Authorization", "Bearer " + owner.token))
                .andExpect(status().isNoContent());
        assertThat(read(mvc.perform(get("/api/v1/partner/me").header("Authorization", "Bearer " + staff.token))
                .andExpect(status().isOk()).andReturn()).path("partnerType").asText()).isEqualTo("INDIVIDUAL");
        assertThat(read(mvc.perform(get("/api/v1/partner/shop/staff").header("Authorization", "Bearer " + owner.token)).andReturn())).isEmpty();

        // An individual partner can be invited too (AUTHZ: P-IND accepts) and can leave on their own.
        postJson("/api/v1/partner/shop/staff", rival.token, Map.of("phone", phone, "fullName", "Thợ")).andExpect(status().isCreated());
        String id2 = read(mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + staff.token)).andReturn()).get(0).path("id").asText();
        assertThat(read(postJson("/api/v1/invitations/" + id2 + "/accept", staff.token, null).andExpect(status().isOk()).andReturn())
                .path("partnerType").asText()).isEqualTo("SHOP_STAFF");
        postJson("/api/v1/partner/shop/leave", staff.token, null).andExpect(status().isNoContent());
        assertThat(read(mvc.perform(get("/api/v1/partner/me").header("Authorization", "Bearer " + staff.token)).andReturn())
                .path("partnerType").asText()).isEqualTo("INDIVIDUAL");
        // Someone who is not staff has nothing to leave.
        postJson("/api/v1/partner/shop/leave", staff.token, null).andExpect(status().isConflict());
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
                "documents", kycDocuments(s, "ID_FRONT", "ID_BACK", "SELFIE")))
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

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    /** Uploads one image per document type through /uploads/kyc and returns the registration's "documents" array. */
    List<Map<String, String>> kycDocuments(Session owner, String... types) throws Exception {
        var docs = new java.util.ArrayList<Map<String, String>>();
        for (String type : types) {
            var uploaded = read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/uploads/kyc")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", type + ".jpg", "image/jpeg", JPEG))
                            .header("Authorization", "Bearer " + owner.token))
                    .andExpect(status().isCreated()).andReturn());
            docs.add(Map.of("documentType", type, "storageKey", uploaded.path("storageKey").asText()));
        }
        return docs;
    }

    /** A registered, admin-approved SHOP owner. */
    Session approvedShop() throws Exception {
        var owner = loginNew();
        postJson("/api/v1/partner-registration", owner.token, Map.of("fullName", "Chủ tiệm", "partnerType", "SHOP",
                "shopName", "Tiệm " + owner.userId.substring(0, 4), "serviceCodes", List.of("tire-patch"),
                "documents", kycDocuments(owner, "ID_FRONT", "ID_BACK", "SELFIE")))
                .andExpect(status().isCreated());
        verifyPartner(owner.userId);
        return owner;
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

    /** The assignment id of the offer made for {@code orderId} (a partner can hold several open offers). */
    String offerIdFor(JsonNode offers, String orderId) {
        for (var offer : offers) {
            if (orderId.equals(offer.path("orderId").asText())) return offer.path("assignmentId").asText();
        }
        throw new AssertionError("No offer for order " + orderId + " in " + offers);
    }

    String orderStatus(Session customer, String orderId) throws Exception {
        return read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + customer.token))
                .andExpect(status().isOk()).andReturn()).path("status").asText();
    }

    /** The partner accepts the offer made for {@code orderId} specifically (they may hold several open offers). */
    void acceptOfferFor(Session partner, String orderId) throws Exception {
        var offers = read(mvc.perform(get("/api/v1/partner/offers").header("Authorization", "Bearer " + partner.token)).andReturn());
        postJson("/api/v1/partner/offers/" + offerIdFor(offers, orderId) + "/accept", partner.token, null).andExpect(status().isOk());
    }

    JsonNode getOrder(Session viewer, String orderId) throws Exception {
        return read(mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer " + viewer.token))
                .andExpect(status().isOk()).andReturn());
    }

    long activeJobs(Session partner) throws Exception {
        return read(mvc.perform(get("/api/v1/partner/stats").header("Authorization", "Bearer " + partner.token))
                .andExpect(status().isOk()).andReturn()).path("activeJobs").asLong();
    }

    String availabilityOf(Session partner) {
        return jdbc.queryForObject("select availability from fixgo_test.partner_profiles where user_id = ?::uuid",
                String.class, partner.userId);
    }

    ResultActions presence(Session partner, String availability, double[] loc) throws Exception {
        return mvc.perform(patch("/api/v1/partner/me/presence").header("Authorization", "Bearer " + partner.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("availability", availability, "lat", loc[0], "lng", loc[1]))));
    }

    /** Accepted order → arrive → check → quote → approve → complete (cash collected). */
    void driveToCompletion(String orderId, Session customer, Session partner) throws Exception {
        postJson("/api/v1/orders/" + orderId + "/arrive", partner.token, null).andExpect(status().isOk());
        postJson("/api/v1/orders/" + orderId + "/check", partner.token, null).andExpect(status().isOk());
        var quote = read(postJson("/api/v1/orders/" + orderId + "/quotes", partner.token, Map.of("items", List.of(
                Map.of("itemType", "LABOR", "description", "Vá xe", "quantity", 1, "unitPrice", 50000))))
                .andExpect(status().isCreated()).andReturn());
        postJson("/api/v1/orders/" + orderId + "/quotes/" + quote.path("id").asText() + "/approve", customer.token, null)
                .andExpect(status().isOk());
        postJson("/api/v1/orders/" + orderId + "/complete", partner.token, null).andExpect(status().isOk());
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
