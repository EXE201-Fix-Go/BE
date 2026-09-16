package com.fixgo.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fixgo.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password, String fullName) { }
