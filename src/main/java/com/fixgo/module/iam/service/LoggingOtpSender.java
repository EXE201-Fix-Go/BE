package com.fixgo.module.iam.service;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.repository.*;
import com.fixgo.module.iam.service.*;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fixgo.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingOtpSender implements OtpSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String phoneE164, String code) {
        // Never log the code itself: the masked target is enough to trace delivery attempts.
        String masked = phoneE164.length() > 4 ? "***" + phoneE164.substring(phoneE164.length() - 4) : "***";
        log.info("OTP issued for {} (SMS_PROVIDER=log: nothing is sent; enable fixgo.auth.otp-dev-echo for dev)", masked);
    }
}
