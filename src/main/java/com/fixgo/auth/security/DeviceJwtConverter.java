package com.fixgo.auth.security;

import com.fixgo.auth.UserDeviceRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

/** Resolves the bearer token to a live device + active user; authorities come from the DB, not the token. */
@Component
public class DeviceJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserDeviceRepository devices;

    public DeviceJwtConverter(UserDeviceRepository devices) { this.devices = devices; }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            UUID deviceId = UUID.fromString(jwt.getClaimAsString("did"));
            var device = devices.findWithUserById(deviceId)
                    .orElseThrow(() -> new InvalidBearerTokenException("Invalid device."));
            var user = device.getUser();
            if (!device.isActive() || !userId.equals(user.getId()) || !user.isActive()) {
                throw new InvalidBearerTokenException("Invalid session.");
            }
            return new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())), userId.toString());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new InvalidBearerTokenException("Invalid token claims.", ex);
        }
    }
}
