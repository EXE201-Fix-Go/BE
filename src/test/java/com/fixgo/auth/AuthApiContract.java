package com.fixgo.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixgo.user.*;
import com.fixgo.admin.AdminBootstrap;
import com.fixgo.admin.BootstrapAdminProperties;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

abstract class AuthApiContract {
    private static final String PASSWORD = "FixGo-test-password!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Validator validator;
    private String fixtureHash;

    @BeforeEach
    void resetDatabase() {
        refreshTokens.deleteAllInBatch();
        sessions.deleteAllInBatch();
        users.deleteAllInBatch();
        fixtureHash = encoder.encode(PASSWORD);
    }

    @Test
    void registerHashesCredentialsAndReturnsOnlySafeCustomerData() throws Exception {
        var result = register("  CUSTOMER@EXAMPLE.COM  ");
        assertThat(result.path("user").path("email").asText()).isEqualTo("customer@example.com");
        assertThat(result.path("user").path("role").asText()).isEqualTo("CUSTOMER");
        assertThat(result.path("user").has("passwordHash")).isFalse();
        assertThat(result.path("expiresIn").asLong()).isEqualTo(900);
        var user = users.findByEmail("customer@example.com").orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(refreshTokens.findSessionIdByTokenHash(TokenService.hash(refresh(result)))).isPresent();
        assertThat(jdbc.queryForObject("select token_hash from refresh_tokens", String.class))
                .isNotEqualTo(refresh(result)).hasSize(64);
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(result)))
                .andExpect(status().isOk()).andExpect(jsonPath("email").value("customer@example.com"))
                .andExpect(jsonPath("passwordHash").doesNotExist());
    }

    @Test
    void duplicateEmailIsCaseInsensitive() throws Exception {
        register("duplicate@example.com");
        postJson("/api/v1/auth/register", registration("DUPLICATE@example.com"))
                .andExpect(status().isConflict());
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void registrationCannotChooseRoleOrAccountStatus() throws Exception {
        for (String field : List.of("role", "status", "id")) {
            var body = new HashMap<>(registration("attack@example.com"));
            body.put(field, "ADMIN");
            postJson("/api/v1/auth/register", body).andExpect(status().isBadRequest());
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void invalidRegistrationAndUnicodePasswordOverflowAreRejected() throws Exception {
        var body = new HashMap<>(registration("not-an-email"));
        body.put("password", "short");
        body.put("fullName", "   ");
        body.put("phoneNumber", "abc");
        postJson("/api/v1/auth/register", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("fieldErrors.email").exists())
                .andExpect(jsonPath("fieldErrors.password").exists())
                .andExpect(jsonPath("fieldErrors.fullName").exists());
        body = new HashMap<>(registration("unicode@example.com"));
        body.put("password", "\u1ea1".repeat(25));
        postJson("/api/v1/auth/register", body).andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    @Test
    void malformedBodiesHaveConsistentSafeErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("trace").doesNotExist());
        postJson("/api/v1/auth/login", Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void loginAcceptsNormalizedEmailAndRejectsBadCredentials() throws Exception {
        seed("customer@example.com", Role.CUSTOMER);
        postJson("/api/v1/auth/login", Map.of("email", " CUSTOMER@EXAMPLE.COM ", "password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("tokenType").value("Bearer"));
        var wrong = body(postJson("/api/v1/auth/login", Map.of("email", "customer@example.com", "password", "wrong"))
                .andExpect(status().isUnauthorized()));
        var absent = body(postJson("/api/v1/auth/login", Map.of("email", "missing@example.com", "password", "wrong"))
                .andExpect(status().isUnauthorized()));
        assertThat(wrong.path("code")).isEqualTo(absent.path("code"));
        assertThat(wrong.path("message")).isEqualTo(absent.path("message"));
    }

    @Test
    void longLoginPasswordReturnsUnauthorizedRatherThanServerError() throws Exception {
        seed("customer@example.com", Role.CUSTOMER);
        postJson("/api/v1/auth/login", Map.of("email", "customer@example.com", "password", "x".repeat(100)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedFailedLoginLocksAccountAndCooldownAllowsLogin() throws Exception {
        var user = seed("customer@example.com", Role.CUSTOMER);
        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", "wrong"))
                    .andExpect(status().isUnauthorized());
        }
        postJson("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select failed_login_attempts from app_users where id = ?", Integer.class,
                user.getId())).isEqualTo(5);
        jdbc.update("update app_users set locked_until = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), user.getId());
        login(user.getEmail());
        assertThat(jdbc.queryForObject("select failed_login_attempts from app_users where id = ?", Integer.class,
                user.getId())).isZero();
    }

    @Test
    void anonymousAndMalformedTokensReturn401() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongSignatureExpiredIssuerAudienceAndMissingClaimsAreRejected() throws Exception {
        var pair = register("customer@example.com");
        var original = jwtDecoder.decode(pair.path("accessToken").asText());
        for (String variant : List.of("expired", "issuer", "audience", "missing-expiry", "missing-audience", "sid", "kind")) {
            var claims = new HashMap<String, Object>(original.getClaims());
            switch (variant) {
                case "expired" -> { claims.put("iat", Instant.now().minusSeconds(120)); claims.put("exp", Instant.now().minusSeconds(60)); }
                case "issuer" -> claims.put("iss", "someone-else");
                case "audience" -> claims.put("aud", List.of("another-api"));
                case "missing-expiry" -> claims.remove("exp");
                case "missing-audience" -> claims.remove("aud");
                case "sid" -> claims.put("sid", UUID.randomUUID().toString());
                case "kind" -> claims.put("token_use", "refresh");
            }
            String forged = signed(claims);
            mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + forged))
                    .andExpect(status().isUnauthorized());
        }
        String access = pair.path("accessToken").asText();
        int signatureStart = access.lastIndexOf('.') + 1;
        String tampered = access.substring(0, signatureStart)
                + (access.charAt(signatureStart) == 'A' ? 'B' : 'A') + access.substring(signatureStart + 1);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotAccessPartnerOrAdminRoutes() throws Exception {
        var customer = register("customer@example.com");
        mvc.perform(get("/api/v1/partner/account").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/users/" + customer.path("user").path("id").asText() + "/role")
                .header("Authorization", bearer(customer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void partnerCanAccessPartnerAccountButNotAdmin() throws Exception {
        seed("partner@example.com", Role.PARTNER);
        var partner = login("partner@example.com");
        mvc.perform(get("/api/v1/partner/account").header("Authorization", bearer(partner)))
                .andExpect(status().isOk()).andExpect(jsonPath("role").value("PARTNER"));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(partner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void profileUpdateIsLimitedToAuthenticatedUserAndSafeFields() throws Exception {
        var first = register("first@example.com");
        var second = register("second@example.com");
        mvc.perform(patch("/api/v1/users/me").header("Authorization", bearer(first))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("fullName", "Updated Name", "phoneNumber", "+84901234567"))))
                .andExpect(status().isOk()).andExpect(jsonPath("fullName").value("Updated Name"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(second)))
                .andExpect(jsonPath("fullName").value("Test Customer"));
        mvc.perform(patch("/api/v1/users/me").header("Authorization", bearer(first))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Admin\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/" + second.path("user").path("id").asText())
                .header("Authorization", bearer(first))).andExpect(status().isForbidden());
    }

    @Test
    void refreshRotatesTokensAndReuseRevokesTheEntireSession() throws Exception {
        var original = register("customer@example.com");
        var rotated = body(postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(original)))
                .andExpect(status().isOk()));
        assertThat(refresh(rotated)).isNotEqualTo(refresh(original));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(rotated))).andExpect(status().isOk());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(original))).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(rotated))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(rotated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void simultaneousRefreshAllowsOnlyOneRotationAndDetectsReuse() throws Exception {
        var pair = register("customer@example.com");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<MvcResult> work = () -> {
                start.await(5, TimeUnit.SECONDS);
                return postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(pair))).andReturn();
            };
            var first = executor.submit(work);
            var second = executor.submit(work);
            start.countDown();
            var one = first.get(15, TimeUnit.SECONDS);
            var two = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(one.getResponse().getStatus(), two.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);
            var success = mapper.readTree((one.getResponse().getStatus() == 200 ? one : two).getResponse().getContentAsString());
            postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(success)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void refreshRejectsUnknownAndExpiredTokens() throws Exception {
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", "a".repeat(43))).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", "" )).andExpect(status().isBadRequest());
        var pair = register("customer@example.com");
        jdbc.update("update auth_sessions set expires_at = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(pair))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(pair))).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesCurrentSessionAndPreservesOtherDevice() throws Exception {
        var first = register("customer@example.com");
        var second = login("customer@example.com");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(first)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(first))).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(first))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(second))).andExpect(status().isOk());
    }

    @Test
    void logoutAllRevokesEveryDevice() throws Exception {
        var first = register("customer@example.com");
        var second = login("customer@example.com");
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", bearer(first)))
                .andExpect(status().isNoContent());
        for (var pair : List.of(first, second)) {
            mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(pair)))
                    .andExpect(status().isUnauthorized());
            postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(pair))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndInvalidatesAllSessions() throws Exception {
        var first = register("customer@example.com");
        var second = login("customer@example.com");
        mvc.perform(post("/api/v1/users/me/password").header("Authorization", bearer(first))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("currentPassword", "wrong", "newPassword", "New-password-2026!"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(first))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/users/me/password").header("Authorization", bearer(first))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", "New-password-2026!"))))
                .andExpect(status().isNoContent());
        for (var pair : List.of(first, second)) {
            mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(pair))).andExpect(status().isUnauthorized());
        }
        postJson("/api/v1/auth/login", Map.of("email", "customer@example.com", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", Map.of("email", "customer@example.com", "password", "New-password-2026!"))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanListReadAndGrantPartnerRoleWithOldSessionsRevoked() throws Exception {
        seed("admin@example.com", Role.ADMIN);
        var admin = login("admin@example.com");
        var customer = register("customer@example.com");
        String id = customer.path("user").path("id").asText();
        mvc.perform(get("/api/v1/admin/users?page=0&size=1").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("items.length()").value(1))
                .andExpect(jsonPath("totalElements").value(2)).andExpect(jsonPath("items[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/v1/admin/users/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        adminPatch(admin, id, "role", "PARTNER").andExpect(status().isOk()).andExpect(jsonPath("role").value("PARTNER"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(customer))).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(customer))).andExpect(status().isUnauthorized());
        var partner = login("customer@example.com");
        mvc.perform(get("/api/v1/partner/account").header("Authorization", bearer(partner))).andExpect(status().isOk());
        adminPatch(admin, id, "role", "CUSTOMER").andExpect(status().isOk());
        mvc.perform(get("/api/v1/partner/account").header("Authorization", bearer(partner)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableBlocksLoginRefreshAndAccessAndReenableDoesNotRestoreOldTokens() throws Exception {
        seed("admin@example.com", Role.ADMIN);
        var admin = login("admin@example.com");
        var customer = register("customer@example.com");
        String id = customer.path("user").path("id").asText();
        adminPatch(admin, id, "status", "DISABLED").andExpect(status().isOk());
        postJson("/api/v1/auth/login", Map.of("email", "customer@example.com", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh(customer))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(customer))).andExpect(status().isUnauthorized());
        adminPatch(admin, id, "status", "ACTIVE").andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(customer))).andExpect(status().isUnauthorized());
        login("customer@example.com");
    }

    @Test
    void adminAccountIsProtectedAndInvalidAdminRequestsHaveCorrectStatus() throws Exception {
        var adminUser = seed("admin@example.com", Role.ADMIN);
        var admin = login("admin@example.com");
        var customer = seed("customer@example.com", Role.CUSTOMER);
        adminPatch(admin, adminUser.getId().toString(), "role", "CUSTOMER").andExpect(status().isConflict());
        adminPatch(admin, adminUser.getId().toString(), "status", "DISABLED").andExpect(status().isConflict());
        adminPatch(admin, customer.getId().toString(), "role", "ADMIN").andExpect(status().isBadRequest());
        adminPatch(admin, customer.getId().toString(), "role", "UNKNOWN").andExpect(status().isBadRequest());
        adminPatch(admin, UUID.randomUUID().toString(), "role", "PARTNER").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/users?page=-1&size=1000").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/users/not-a-uuid").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browserPreflightAcceptsConfiguredOriginAndRejectsOtherOrigin() throws Exception {
        mvc.perform(options("/api/v1/users/me").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/v1/users/me").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bootstrapCreatesAdminAndDoesNotResetExistingPassword() throws Exception {
        bootstrap("admin@example.com", PASSWORD).run(new DefaultApplicationArguments());
        var first = users.findByEmail("admin@example.com").orElseThrow();
        assertThat(first.getRole()).isEqualTo(Role.ADMIN);
        bootstrap("ADMIN@example.com", "Another-valid-password!").run(new DefaultApplicationArguments());
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByEmail("admin@example.com").orElseThrow().getPasswordHash())
                .isEqualTo(first.getPasswordHash());
        login("admin@example.com");
    }

    @Test
    void bootstrapNeverPromotesAnExistingCustomer() {
        seed("existing@example.com", Role.CUSTOMER);
        assertThatThrownBy(() -> bootstrap("existing@example.com", PASSWORD).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.findByEmail("existing@example.com").orElseThrow().getRole()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void bootstrapHasNoDefaultAdminAndRejectsIncompleteOrWeakCredentials() {
        bootstrap("", "").run(new DefaultApplicationArguments());
        assertThat(users.count()).isZero();
        assertThatThrownBy(() -> bootstrap("admin@example.com", "").run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bootstrap("admin@example.com", "short").run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.count()).isZero();
    }

    private AdminBootstrap bootstrap(String email, String password) {
        return new AdminBootstrap(new BootstrapAdminProperties(email, password, "Test Admin"),
                users, encoder, validator, Clock.systemUTC());
    }

    private String signed(Map<String, Object> claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().claims(map -> map.putAll(claims)).build())).getTokenValue();
    }

    private User seed(String email, Role role) {
        return users.saveAndFlush(new User(email, fixtureHash, "Test User", null, role, Instant.now()));
    }

    private Map<String, String> registration(String email) {
        return Map.of("email", email, "password", PASSWORD, "fullName", "Test Customer");
    }

    private JsonNode register(String email) throws Exception {
        return body(postJson("/api/v1/auth/register", registration(email)).andExpect(status().isCreated()));
    }

    private JsonNode login(String email) throws Exception {
        return body(postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)).andExpect(status().isOk()));
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private ResultActions adminPatch(JsonNode admin, String id, String field, String value) throws Exception {
        return mvc.perform(patch("/api/v1/admin/users/" + id + "/" + field).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(field, value))));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String bearer(JsonNode pair) { return "Bearer " + pair.path("accessToken").asText(); }
    private String refresh(JsonNode pair) { return pair.path("refreshToken").asText(); }
}
