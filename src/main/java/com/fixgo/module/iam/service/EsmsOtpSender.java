package com.fixgo.module.iam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixgo.shared.config.AuthProperties;
import com.fixgo.shared.config.SmsProperties;
import com.fixgo.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends OTP codes through eSMS.vn (POST SendMultipleMessage_V4_post_json, SmsType 2 "customer care").
 * Docs: https://developers.esms.vn/en/esms-api/send-sms-api/send-otp-customer-care-message
 * Success is CodeResult "100". The code and the API credentials are never logged.
 */
@Component
@Primary
@ConditionalOnProperty(name = "fixgo.sms.provider", havingValue = "esms")
public class EsmsOtpSender implements OtpSender {
    private static final Logger log = LoggerFactory.getLogger(EsmsOtpSender.class);

    private final SmsProperties.Esms config;
    private final long otpMinutes;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public EsmsOtpSender(SmsProperties properties, AuthProperties auth, ObjectMapper mapper) {
        this.config = properties.esms();
        this.otpMinutes = Math.max(1, auth.otpTtl().toMinutes());
        this.mapper = mapper;
        if (isBlank(config.apiKey()) || isBlank(config.secretKey()) || isBlank(config.brandname())) {
            throw new IllegalStateException(
                    "SMS_PROVIDER=esms needs ESMS_API_KEY, ESMS_SECRET_KEY and ESMS_BRANDNAME (a brandname registered with eSMS).");
        }
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    @Override
    public void send(String phoneE164, String code) {
        String masked = phoneE164.length() > 4 ? "***" + phoneE164.substring(phoneE164.length() - 4) : "***";
        try {
            var response = http.send(request(phoneE164, code), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("sms.esms HTTP {} for {}", response.statusCode(), masked);
                throw deliveryFailed();
            }
            var json = mapper.readTree(response.body());
            String result = json.path("CodeResult").asText("");
            if (!"100".equals(result)) {
                // 101 bad credentials, 104 brandname unknown, 124 duplicate, 146 template not registered, 99 gateway error.
                log.error("sms.esms rejected {} CodeResult={} message={}", masked, result, json.path("ErrorMessage").asText(""));
                throw deliveryFailed();
            }
            log.info("sms.esms sent to {} smsId={}{}", masked, json.path("SMSID").asText(""), config.sandbox() ? " (sandbox)" : "");
        } catch (IOException ex) {
            log.error("sms.esms request failed for {}: {}", masked, ex.toString());
            throw deliveryFailed();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw deliveryFailed();
        }
    }

    private HttpRequest request(String phoneE164, String code) throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("ApiKey", config.apiKey());
        body.put("SecretKey", config.secretKey());
        body.put("Phone", phoneE164.startsWith("+") ? phoneE164.substring(1) : phoneE164);      // 84901234567
        body.put("Content", config.contentTemplate().replace("{code}", code).replace("{minutes}", Long.toString(otpMinutes)));
        body.put("SmsType", config.smsType());
        body.put("Brandname", config.brandname());
        body.put("IsUnicode", "0");
        body.put("Sandbox", config.sandbox() ? "1" : "0");
        return HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private static ApiException deliveryFailed() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "OTP_DELIVERY_FAILED",
                "Không gửi được tin nhắn mã xác thực. Vui lòng thử lại sau ít phút.");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
