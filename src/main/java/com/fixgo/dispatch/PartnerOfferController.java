package com.fixgo.dispatch;

import com.fixgo.common.Actor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partner")
@PreAuthorize("hasRole('PARTNER')")
public class PartnerOfferController {
    private final DispatchService dispatch;

    public PartnerOfferController(DispatchService dispatch) { this.dispatch = dispatch; }

    @GetMapping("/offers")
    public List<DispatchDtos.OfferResponse> offers(Authentication auth) { return dispatch.listOffers(Actor.of(auth)); }

    @PostMapping("/offers/{assignmentId}/accept")
    public DispatchDtos.OfferResponse accept(Authentication auth, @PathVariable UUID assignmentId,
                                             @Valid @RequestBody(required = false) DispatchDtos.AcceptRequest request) {
        return dispatch.accept(Actor.of(auth), assignmentId, request);
    }

    @PostMapping("/offers/{assignmentId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(Authentication auth, @PathVariable UUID assignmentId,
                        @Valid @RequestBody(required = false) DispatchDtos.DeclineRequest request) {
        dispatch.decline(Actor.of(auth), assignmentId, request == null ? null : request.reason());
    }

    @GetMapping("/jobs")
    public List<DispatchDtos.OfferResponse> jobs(Authentication auth) { return dispatch.listActiveJobs(Actor.of(auth)); }
}
