package com.fixgo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties("fixgo.cors")
public record CorsProperties(List<String> allowedOrigins) { }
