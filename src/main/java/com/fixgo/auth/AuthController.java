package com.fixgo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/otp")
    public AuthDtos.OtpRequestResult requestOtp(@Valid @RequestBody AuthDtos.OtpRequest request,
                                                HttpServletRequest http) {
        return service.requestOtp(request.phone(), clientIp(http));
    }

    @PostMapping("/otp/verify")
    public AuthDtos.TokenResponse verifyOtp(@Valid @RequestBody AuthDtos.OtpVerifyRequest request) {
        return service.verifyOtp(request);
    }

    @PostMapping("/refresh")
    public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return service.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        service.logout(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("did")));
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal Jwt jwt) {
        service.logoutAll(UUID.fromString(jwt.getSubject()));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].strip();
        return request.getRemoteAddr();
    }
}
