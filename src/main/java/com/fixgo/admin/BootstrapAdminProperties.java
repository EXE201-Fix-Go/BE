package com.fixgo.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** First ADMIN is provisioned by phone; they sign in with OTP like everyone else (RB-09, C-06). */
@ConfigurationProperties("fixgo.bootstrap-admin")
public record BootstrapAdminProperties(String phone, String fullName) { }
