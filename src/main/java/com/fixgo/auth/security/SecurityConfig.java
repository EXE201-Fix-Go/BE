package com.fixgo.auth.security;

import com.fixgo.auth.AuthProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    SecretKey jwtKey(AuthProperties properties) {
        String encoded = properties.jwtSecret();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Set JWT_SECRET to a Base64-encoded random key (at least 32 bytes).");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes.");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, AuthProperties properties, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(5))
                    && jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())
                    && "access".equals(jwt.getClaimAsString("token_use"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid access token claims.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(properties.issuer()), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DeviceJwtConverter converter,
                                           SecurityErrorHandler errors) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/otp", "/api/v1/auth/otp/verify",
                                "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/services").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/partner/**").hasRole("PARTNER")
                        // Orders, quotes, payments, reviews: per-order ownership is checked in the service layer (NT-02).
                        .requestMatchers("/api/v1/orders/**", "/api/v1/users/me", "/api/v1/users/me/**",
                                "/api/v1/partner-registration", "/api/v1/auth/logout", "/api/v1/auth/logout-all")
                            .authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
