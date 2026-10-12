package com.fixgo.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixgo.module.iam.service.EsmsOtpSender;
import com.fixgo.shared.config.AuthProperties;
import com.fixgo.shared.config.SmsProperties;
import com.fixgo.shared.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The eSMS adapter against a local stand-in for rest.esms.vn: request shape, success and every failure path. */
class EsmsOtpSenderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> reply = new AtomicReference<>("{\"CodeResult\":\"100\",\"SMSID\":\"abc\"}");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private EsmsOtpSender sender(String url, boolean sandbox, Duration timeout) {
        var esms = new SmsProperties.Esms(url, "key", "secret", "FixGo", "2", sandbox, null, timeout);
        var auth = new AuthProperties("fixgo", "fixgo-api", "x".repeat(32), Duration.ofMinutes(15), Duration.ofDays(30),
                Duration.ofMinutes(5), 5, 5, 30, false, 0);
        return new EsmsOtpSender(new SmsProperties("esms", esms), auth, mapper);
    }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/send"; }

    @Test
    void sendsTheDocumentedRequestAndTreatsCode100AsDelivered() {
        sender(url(), false, null).send("+84901234567", "123456");
        var body = received.get();
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(body.path("ApiKey").asText()).isEqualTo("key");
        assertThat(body.path("SecretKey").asText()).isEqualTo("secret");
        assertThat(body.path("Phone").asText()).isEqualTo("84901234567");
        assertThat(body.path("SmsType").asText()).isEqualTo("2");
        assertThat(body.path("Brandname").asText()).isEqualTo("FixGo");
        assertThat(body.path("IsUnicode").asText()).isEqualTo("0");
        assertThat(body.path("Sandbox").asText()).isEqualTo("0");
        assertThat(body.path("Content").asText()).isEqualTo(
                "123456 la ma xac thuc Fix&Go cua ban, co hieu luc 5 phut. Tuyet doi khong chia se ma nay voi bat ky ai.");
    }

    @Test
    void sandboxModeIsForwarded() {
        sender(url(), true, null).send("+84901234567", "123456");
        assertThat(received.get().path("Sandbox").asText()).isEqualTo("1");
    }

    @Test
    void anyOtherCodeResultFailsDeliveryWithoutLeakingProviderDetails() {
        for (String code : new String[] {"101", "104", "124", "146", "99"}) {
            reply.set("{\"CodeResult\":\"" + code + "\",\"ErrorMessage\":\"nope\"}");
            assertThatThrownBy(() -> sender(url(), false, null).send("+84901234567", "123456"))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo("OTP_DELIVERY_FAILED");
                        assertThat(ex.getMessage()).doesNotContain("nope").doesNotContain("key");
                    });
        }
    }

    @Test
    void aNon200ResponseOrAnUnreachableGatewayFailsDelivery() {
        status.set(500);
        assertThatThrownBy(() -> sender(url(), false, null).send("+84901234567", "123456"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("OTP_DELIVERY_FAILED"));
        assertThatThrownBy(() -> sender("http://127.0.0.1:1/send", false, Duration.ofSeconds(1)).send("+84901234567", "123456"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("OTP_DELIVERY_FAILED"));
    }

    @Test
    void refusesToStartWithoutCredentialsOrBrandname() {
        var auth = new AuthProperties("fixgo", "fixgo-api", "x".repeat(32), Duration.ofMinutes(15), Duration.ofDays(30),
                Duration.ofMinutes(5), 5, 5, 30, false, 0);
        var missing = new SmsProperties("esms", new SmsProperties.Esms(null, "key", "secret", "", null, false, null, null));
        assertThatThrownBy(() -> new EsmsOtpSender(missing, auth, mapper)).hasMessageContaining("ESMS_BRANDNAME");
    }
}
