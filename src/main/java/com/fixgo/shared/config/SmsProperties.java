package com.fixgo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How OTP codes reach people. {@code provider}: {@code log} only logs (local development, together with
 * {@code fixgo.auth.otp-dev-echo}); {@code esms} sends through the eSMS.vn REST API.
 */
@ConfigurationProperties("fixgo.sms")
public record SmsProperties(String provider, Esms esms) {

    public SmsProperties {
        provider = provider == null || provider.isBlank() ? "log" : provider.strip().toLowerCase();
        esms = esms == null ? new Esms(null, null, null, null, null, false, null, null) : esms;
    }

    public boolean isLogOnly() { return "log".equals(provider); }

    /** eSMS.vn customer-care message (SmsType 2): needs a registered Brandname and a registered content template. */
    public record Esms(String url, String apiKey, String secretKey, String brandname, String smsType, boolean sandbox,
                       String contentTemplate, Duration timeout) {
        public static final String DEFAULT_URL = "https://rest.esms.vn/MainService.svc/json/SendMultipleMessage_V4_post_json/";
        /** ASCII only (1 SMS, no Unicode surcharge). The text must match the template registered with eSMS exactly. */
        public static final String DEFAULT_TEMPLATE =
                "{code} la ma xac thuc Fix&Go cua ban, co hieu luc {minutes} phut. Tuyet doi khong chia se ma nay voi bat ky ai.";

        public Esms {
            url = blank(url) ? DEFAULT_URL : url.strip();
            smsType = blank(smsType) ? "2" : smsType.strip();
            contentTemplate = blank(contentTemplate) ? DEFAULT_TEMPLATE : contentTemplate;
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }

        private static boolean blank(String s) { return s == null || s.isBlank(); }
    }
}
