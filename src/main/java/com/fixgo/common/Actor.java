package com.fixgo.common;

import com.fixgo.user.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import java.util.UUID;

/**
 * The authenticated caller. The role comes from the authorities the JWT converter loaded from the
 * database, so a role change takes effect on the next request even with an old token.
 */
public record Actor(UUID userId, Role role) {
    public static Actor of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InvalidBearerTokenException("Authentication required.");
        }
        Role role = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> Role.valueOf(a.substring(5)))
                .findFirst()
                .orElseThrow(() -> new InvalidBearerTokenException("Missing role."));
        return new Actor(UUID.fromString(authentication.getName()), role);
    }

    public boolean is(Role expected) { return role == expected; }
}
