package com.fixgo.partner;

import com.fixgo.common.Actor;
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
    private final com.fixgo.dispatch.DispatchService dispatch;

    public PartnerController(PartnerProfileService service, PartnerStatsService stats,
                             com.fixgo.dispatch.DispatchService dispatch) {
        this.service = service;
        this.stats = stats;
        this.dispatch = dispatch;
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

    @GetMapping("/shop/staff")
    public List<PartnerDtos.StaffResponse> staff(Authentication auth) { return service.listStaff(Actor.of(auth)); }

    @PostMapping("/shop/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public List<PartnerDtos.StaffResponse> invite(Authentication auth, @Valid @RequestBody PartnerDtos.InviteStaffRequest request) {
        return service.inviteStaff(Actor.of(auth), request);
    }
}
