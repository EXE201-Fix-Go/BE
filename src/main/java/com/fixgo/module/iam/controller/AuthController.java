package com.fixgo.module.iam.controller;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.repository.*;
import com.fixgo.module.iam.service.*;


import com.fixgo.shared.config.AuthProperties;
import com.fixgo.shared.util.ClientIp;
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
    private final AuthProperties properties;

    public AuthController(AuthService service, AuthProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/otp")
    public AuthDtos.OtpRequestResult requestOtp(@Valid @RequestBody AuthDtos.OtpRequest request,
                                                HttpServletRequest http) {
        return service.requestOtp(request.phone(), ClientIp.resolve(http, properties.trustedProxyHops()));
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
}
