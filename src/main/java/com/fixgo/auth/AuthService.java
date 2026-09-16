package com.fixgo.auth;

import com.fixgo.common.ApiException;
import com.fixgo.user.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, AuthSessionRepository sessions, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwords, TokenService tokens, AuthProperties properties, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
        dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email is already registered.");
        }
        var user = users.saveAndFlush(new User(request.email(), passwords.encode(request.password()),
                request.fullName(), request.phoneNumber(), Role.CUSTOMER, clock.instant()));
        return startSession(user);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        var user = users.lockByEmail(request.email()).orElse(null);
        boolean validPassword = matches(request.password(), user == null ? dummyPasswordHash : user.getPasswordHash());
        var now = clock.instant();
        if (user == null || !user.canLogin(now)) throw invalidCredentials();
        if (!validPassword) {
            user.recordLoginFailure(now, properties.maxLoginFailures(), properties.loginLockDuration());
            throw invalidCredentials();
        }
        user.resetLoginFailures(now);
        return startSession(user);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthDtos.TokenResponse refresh(String rawToken) {
        String hash = TokenService.hash(rawToken);
        UUID sessionId = refreshTokens.findSessionIdByTokenHash(hash).orElseThrow(this::invalidRefresh);
        UUID userId = sessions.findUserIdById(sessionId).orElseThrow(this::invalidRefresh);
        // Every session mutation locks the account first, then the session, to serialize rotation/revocation.
        var user = users.lockById(userId).orElseThrow(this::invalidRefresh);
        var session = sessions.lockById(sessionId).orElseThrow(this::invalidRefresh);
        var token = refreshTokens.findByTokenHash(hash).orElseThrow(this::invalidRefresh);
        var now = clock.instant();
        if (!session.isActive(now) || user.getStatus() != AccountStatus.ACTIVE) throw invalidRefresh();
        if (token.isUsed()) {
            session.revoke(now);
            throw invalidRefresh();
        }
        token.markUsed(now);
        return tokens.issue(session);
    }

    @Transactional
    public void logout(UUID userId, UUID sessionId) {
        users.lockById(userId).orElseThrow(this::invalidRefresh);
        var session = sessions.lockById(sessionId).orElseThrow(this::invalidRefresh);
        if (!session.getUser().getId().equals(userId)) throw invalidRefresh();
        session.revoke(clock.instant());
    }

    @Transactional
    public void logoutAll(UUID userId) {
        users.lockById(userId).orElseThrow(this::invalidRefresh);
        sessions.revokeAllForUser(userId, clock.instant());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void changePassword(UUID userId, AuthDtos.ChangePasswordRequest request) {
        var user = users.lockById(userId).orElseThrow(this::invalidCredentials);
        if (!user.canLogin(clock.instant())) throw invalidCredentials();
        if (!matches(request.currentPassword(), user.getPasswordHash())) {
            user.recordLoginFailure(clock.instant(), properties.maxLoginFailures(), properties.loginLockDuration());
            throw invalidCredentials();
        }
        user.changePassword(passwords.encode(request.newPassword()), clock.instant());
        sessions.revokeAllForUser(userId, clock.instant());
    }

    private boolean matches(String password, String hash) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(password, hash);
    }

    private AuthDtos.TokenResponse startSession(User user) {
        var now = clock.instant();
        var session = sessions.save(new AuthSession(user, now, now.plus(properties.refreshTokenTtl())));
        return tokens.issue(session);
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Unable to sign in with these credentials.");
    }

    private ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired.");
    }
}
