package com.fixgo.module.partner.controller;

import com.fixgo.module.partner.dto.InvitationDtos.InvitationResponse;
import com.fixgo.module.partner.dto.PartnerDtos;
import com.fixgo.module.partner.service.ShopInvitationService;
import com.fixgo.shared.util.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Shop staff invitations. The shop owner side lives under /partner/shop (partners only); the invited person
 * (still a customer, or an individual partner) uses /invitations, which any signed-in non-admin can reach.
 */
@RestController
@RequestMapping("/api/v1")
public class InvitationController {
    private final ShopInvitationService service;

    public InvitationController(ShopInvitationService service) {
        this.service = service;
    }

    @GetMapping("/partner/shop/invitations")
    public List<InvitationResponse> forShop(Authentication auth) { return service.listForShop(Actor.of(auth)); }

    @DeleteMapping("/partner/shop/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(Authentication auth, @PathVariable UUID id) { service.cancel(Actor.of(auth), id); }

    @DeleteMapping("/partner/shop/staff/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeStaff(Authentication auth, @PathVariable UUID userId) { service.removeStaff(Actor.of(auth), userId); }

    @PostMapping("/partner/shop/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(Authentication auth) { service.leave(Actor.of(auth)); }

    @GetMapping("/invitations")
    public List<InvitationResponse> mine(Authentication auth) { return service.listMine(Actor.of(auth)); }

    @PostMapping("/invitations/{id}/accept")
    public PartnerDtos.ProfileResponse accept(Authentication auth, @PathVariable UUID id) {
        return service.accept(Actor.of(auth), id);
    }

    @PostMapping("/invitations/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(Authentication auth, @PathVariable UUID id) { service.decline(Actor.of(auth), id); }
}
