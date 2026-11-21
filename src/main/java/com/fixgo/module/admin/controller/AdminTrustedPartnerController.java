package com.fixgo.module.admin.controller;

import com.fixgo.module.admin.dto.TrustedPartnerDtos;
import com.fixgo.module.admin.service.AdminTrustedPartnerService;
import com.fixgo.shared.util.Actor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Admin adds a partner they verified in person; the system stores no KYC files for them. */
@RestController
@RequestMapping("/api/v1/admin/partners/trusted")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTrustedPartnerController {
    private final AdminTrustedPartnerService partners;

    public AdminTrustedPartnerController(AdminTrustedPartnerService partners) { this.partners = partners; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrustedPartnerDtos.Response create(Authentication auth, @Valid @RequestBody TrustedPartnerDtos.CreateRequest request) {
        return partners.create(Actor.of(auth), request);
    }
}
