package com.fixgo.partner;

import com.fixgo.user.UserResponse;
import com.fixgo.user.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partner/account")
@PreAuthorize("hasRole('PARTNER')")
public class PartnerAccountController {
    private final UserService users;

    public PartnerAccountController(UserService users) { this.users = users; }

    @GetMapping
    public UserResponse account(@AuthenticationPrincipal Jwt jwt) {
        return users.get(UUID.fromString(jwt.getSubject()));
    }
}
