package com.fixgo.auth;

/** Delivery channel for OTP codes. The SMS provider is not chosen yet (BRD §8), so the default only logs. */
public interface OtpSender {
    void send(String phoneE164, String code);
}
