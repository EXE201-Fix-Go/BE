package com.fixgo.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(value = OtpSender.class, ignored = LoggingOtpSender.class)
public class LoggingOtpSender implements OtpSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String phoneE164, String code) {
        // Never log the code itself: the masked target is enough to trace delivery attempts.
        String masked = phoneE164.length() > 4 ? "***" + phoneE164.substring(phoneE164.length() - 4) : "***";
        log.info("OTP issued for {} (SMS provider not configured; enable fixgo.auth.otp-dev-echo for dev)", masked);
    }
}
