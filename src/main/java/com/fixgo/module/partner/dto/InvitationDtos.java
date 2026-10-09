package com.fixgo.module.partner.dto;

import java.time.Instant;
import java.util.UUID;

public final class InvitationDtos {
    private InvitationDtos() { }

    /** status is PENDING, ACCEPTED, DECLINED, CANCELLED, or EXPIRED (a PENDING invitation past its deadline). */
    public record InvitationResponse(UUID id, UUID shopId, String shopName, String inviteePhone, String inviteeName,
                                     String status, Instant createdAt, Instant expiresAt) { }
}
