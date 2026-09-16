package com.fixgo.auth;

import com.fixgo.user.UserResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, AuthProperties properties, RefreshTokenRepository refreshTokens,
                        Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    public AuthDtos.TokenResponse issue(AuthSession session) {
        var now = clock.instant();
        var expiresAt = now.plus(properties.accessTokenTtl());
        if (expiresAt.isAfter(session.getExpiresAt())) expiresAt = session.getExpiresAt();
        var user = session.getUser();
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject(user.getId().toString()).issuedAt(now).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString()).claim("sid", session.getId().toString())
                .claim("role", user.getRole().name()).claim("token_use", "access").build();
        String access = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(session, hash(refresh), now));
        return new AuthDtos.TokenResponse(access, refresh, "Bearer", Duration.between(now, expiresAt).toSeconds(),
                session.getExpiresAt(), UserResponse.from(user));
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
