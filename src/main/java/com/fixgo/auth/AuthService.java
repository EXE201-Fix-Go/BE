package com.fixgo.auth;

import com.fixgo.common.ApiException;
import com.fixgo.common.PhoneNumbers;
import com.fixgo.user.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** OTP + self-managed JWT (QD-12). No passwords anywhere (C-06). */
@Service
public class AuthService {
    private final OtpChallengeRepository challenges;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final UserDeviceRepository devices;
    private final RefreshTokenRepository refreshTokens;
    private final UserService userService;
    private final TokenService tokens;
    private final OtpSender sender;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(OtpChallengeRepository challenges, UserRepository users, UserIdentityRepository identities,
                       UserDeviceRepository devices, RefreshTokenRepository refreshTokens, UserService userService,
                       TokenService tokens, OtpSender sender, AuthProperties properties, Clock clock) {
        this.challenges = challenges;
        this.users = users;
        this.identities = identities;
        this.devices = devices;
        this.refreshTokens = refreshTokens;
        this.userService = userService;
        this.tokens = tokens;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /** RB-04: rate-limited per target and per IP. */
    @Transactional
    public AuthDtos.OtpRequestResult requestOtp(String rawPhone, String requestIp) {
        String phone = PhoneNumbers.toE164(rawPhone);
        var now = clock.instant();
        var hourAgo = now.minus(Duration.ofHours(1));
        if (challenges.countByTargetAndCreatedAtAfter(phone, hourAgo) >= properties.otpMaxPerTargetPerHour()
                || (requestIp != null && challenges.countByRequestIpAndCreatedAtAfter(requestIp, hourAgo)
                    >= properties.otpMaxPerIpPerHour())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED",
                    "Too many OTP requests. Please wait before trying again.");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        // The hash is salted with the challenge id so equal codes never share a hash (RB-03).
        UUID id = UUID.randomUUID();
        var challenge = challenges.save(new OtpChallenge(id, phone, OtpPurpose.LOGIN, hashOf(id, code),
                properties.otpMaxAttempts(), now, now.plus(properties.otpTtl()), requestIp));
        sender.send(phone, code);
        return new AuthDtos.OtpRequestResult(challenge.getId(), properties.otpTtl().toSeconds(),
                properties.otpDevEcho() ? code : null);
    }

    /** RB-03: consumed inside the same transaction as the check; RB-05: tokens bound to a device. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthDtos.TokenResponse verifyOtp(AuthDtos.OtpVerifyRequest request) {
        var now = clock.instant();
        var challenge = challenges.lockById(request.otpId()).orElseThrow(AuthService::invalidOtp);
        if (!challenge.isOpen(now)) throw invalidOtp();
        if (!MessageDigest.isEqual(hashOf(challenge.getId(), request.code()).getBytes(StandardCharsets.UTF_8),
                challenge.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            challenge.recordFailure();
            throw invalidOtp();
        }
        challenge.consume(now);

        var identity = identities.findByProviderAndProviderUid(IdentityProvider.PHONE, challenge.getTarget())
                .orElseGet(() -> {
                    var user = users.save(new User(Role.CUSTOMER, null, now));
                    return identities.save(new UserIdentity(user, IdentityProvider.PHONE, challenge.getTarget(),
                            true, now, now));
                });
        identity.markVerified(now);
        var user = identity.getUser();
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "This account is locked.");
        }
        user.recordLogin(now);
        String fingerprint = request.deviceFingerprint() == null || request.deviceFingerprint().isBlank()
                ? "anon-" + UUID.randomUUID() : request.deviceFingerprint().strip();
        var device = devices.save(new UserDevice(user, fingerprint,
                request.platform() == null ? DevicePlatform.WEB : request.platform(), now));
        return tokens.issue(device, userService.toResponse(user)).response();
    }

    /** Reuse of an already-rotated token revokes the whole device chain. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthDtos.TokenResponse refresh(String rawToken) {
        var now = clock.instant();
        var token = refreshTokens.lockByTokenHash(TokenService.hash(rawToken)).orElseThrow(AuthService::invalidRefresh);
        var device = devices.lockById(token.getDevice().getId()).orElseThrow(AuthService::invalidRefresh);
        if (token.isUsed()) {
            device.revoke(now, null);
            throw invalidRefresh();
        }
        var user = users.lockById(device.getUser().getId()).orElseThrow(AuthService::invalidRefresh);
        if (!token.isLive(now) || !device.isActive() || !user.isActive()) throw invalidRefresh();
        device.touch(now);
        var issued = tokens.issue(device, userService.toResponse(user));
        token.rotateTo(issued.row(), now);
        return issued.response();
    }

    @Transactional
    public void logout(UUID userId, UUID deviceId) {
        var device = devices.lockById(deviceId).orElseThrow(AuthService::invalidRefresh);
        if (!device.getUser().getId().equals(userId)) throw invalidRefresh();
        device.revoke(clock.instant(), userId);
    }

    @Transactional
    public void logoutAll(UUID userId) {
        devices.revokeAllForUser(userId, clock.instant(), userId);
    }

    static String hashOf(UUID otpId, String code) {
        return TokenService.hash(otpId + ":" + code);
    }

    private static ApiException invalidOtp() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_OTP", "The code is invalid or has expired.");
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired.");
    }
}
