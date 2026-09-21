package com.fixgo.partner;

import com.fixgo.common.Actor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Reachable by a freshly OTP-verified CUSTOMER account: this is how someone becomes a partner. */
@RestController
@RequestMapping("/api/v1/partner-registration")
public class PartnerRegistrationController {
    private final PartnerProfileService service;

    public PartnerRegistrationController(PartnerProfileService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PartnerDtos.ProfileResponse register(Authentication auth, @Valid @RequestBody PartnerDtos.RegisterRequest request) {
        return service.register(Actor.of(auth), request);
    }
}
