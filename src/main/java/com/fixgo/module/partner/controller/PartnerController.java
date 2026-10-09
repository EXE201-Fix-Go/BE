package com.fixgo.module.partner.controller;
import com.fixgo.module.partner.entity.*;
import com.fixgo.module.partner.enums.*;
import com.fixgo.module.partner.dto.*;
import com.fixgo.module.partner.repository.*;
import com.fixgo.module.partner.service.*;


import com.fixgo.shared.util.Actor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/partner")
@PreAuthorize("hasRole('PARTNER')")
public class PartnerController {
    private final PartnerProfileService service;
    private final PartnerStatsService stats;
    private final com.fixgo.module.dispatch.service.DispatchService dispatch;
    private final com.fixgo.module.partner.service.ShopInvitationService invitations;

    public PartnerController(PartnerProfileService service, PartnerStatsService stats,
                             com.fixgo.module.dispatch.service.DispatchService dispatch,
                             com.fixgo.module.partner.service.ShopInvitationService invitations) {
        this.service = service;
        this.stats = stats;
        this.dispatch = dispatch;
        this.invitations = invitations;
    }

    @GetMapping("/dashboard")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public PartnerDtos.DashboardResponse dashboard(Authentication auth) {
        var actor = Actor.of(auth);
        return new PartnerDtos.DashboardResponse(service.me(actor), dispatch.listOffers(actor),
                dispatch.listActiveJobs(actor), stats.stats(actor));
    }

    @GetMapping("/me")
    public PartnerDtos.ProfileResponse me(Authentication auth) { return service.me(Actor.of(auth)); }

    @GetMapping("/stats")
    public PartnerDtos.StatsResponse stats(Authentication auth) { return stats.stats(Actor.of(auth)); }

    @PatchMapping("/me/presence")
    public PartnerDtos.ProfileResponse presence(Authentication auth, @Valid @RequestBody PartnerDtos.PresenceRequest request) {
        return service.updatePresence(Actor.of(auth), request);
    }

    /**
     * The partner app sends its position every minute while online. Unlike /me/presence this only moves the
     * position, so a ping that races with accepting or completing an order can never flip ONLINE/BUSY.
     */
    @PutMapping("/me/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void location(Authentication auth, @Valid @RequestBody PartnerDtos.LocationRequest request) {
        service.updateLocation(Actor.of(auth), request);
    }

    /**
     * Sends (new) KYC documents for the signed-in partner: shop staff who joined through an invitation have none
     * yet, and a rejected partner has to send better ones. The profile goes back to PENDING for the admin.
     */
    @PutMapping("/me/documents")
    public PartnerDtos.ProfileResponse documents(Authentication auth, @Valid @RequestBody PartnerDtos.DocumentsRequest request) {
        return service.submitDocuments(Actor.of(auth), request);
    }

    @GetMapping("/shop/staff")
    public List<PartnerDtos.StaffResponse> staff(Authentication auth) { return service.listStaff(Actor.of(auth)); }

    @PostMapping("/shop/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public List<PartnerDtos.StaffResponse> invite(Authentication auth, @Valid @RequestBody PartnerDtos.InviteStaffRequest request) {
        // Creates a pending invitation only: the invitee's account is untouched until they accept. The staff list is
        // returned as before so existing clients keep working; pending ones are at GET /partner/shop/invitations.
        invitations.invite(Actor.of(auth), request);
        return service.listStaff(Actor.of(auth));
    }
}
