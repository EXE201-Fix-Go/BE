package com.fixgo.admin;

import com.fixgo.common.Actor;
import com.fixgo.partner.PartnerDtos;
import com.fixgo.partner.PartnerProfileService;
import com.fixgo.user.AccountStatus;
import com.fixgo.user.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminUserController {
    private final AdminUserService users;
    private final PartnerProfileService partners;

    public AdminUserController(AdminUserService users, PartnerProfileService partners) {
        this.users = users;
        this.partners = partners;
    }

    @GetMapping("/users")
    public AdminUserService.UserPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return users.list(page, size);
    }

    @GetMapping("/users/{id}")
    public UserResponse get(@PathVariable UUID id) { return users.get(id); }

    @PatchMapping("/users/{id}/status")
    public UserResponse status(Authentication auth, @PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return users.changeStatus(Actor.of(auth).userId(), id, request.status());
    }

    /** BR06: partners only start receiving broadcasts after an admin approves their KYC. */
    @PostMapping("/partners/{id}/verify")
    public PartnerDtos.ProfileResponse verify(Authentication auth, @PathVariable UUID id,
                                              @Valid @RequestBody PartnerDtos.VerifyRequest request) {
        return partners.verify(Actor.of(auth), id, request.status());
    }

    public record StatusRequest(@NotNull AccountStatus status) { }
}
