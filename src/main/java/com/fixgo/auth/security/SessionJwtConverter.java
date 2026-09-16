package com.fixgo.auth.security;

import com.fixgo.auth.AuthSessionRepository;
import com.fixgo.user.AccountStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
public class SessionJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final AuthSessionRepository sessions;
    private final Clock clock;

    public SessionJwtConverter(AuthSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
            var session = sessions.findWithUserById(sessionId)
                    .orElseThrow(() -> new InvalidBearerTokenException("Invalid session."));
            var user = session.getUser();
            if (!session.isActive(clock.instant()) || !userId.equals(user.getId())
                    || user.getStatus() != AccountStatus.ACTIVE) {
                throw new InvalidBearerTokenException("Invalid session.");
            }
            // Read current authority from the database so role changes never rely on stale JWT claims.
            return new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())), userId.toString());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new InvalidBearerTokenException("Invalid token claims.", ex);
        }
    }
}
