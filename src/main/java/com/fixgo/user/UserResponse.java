package com.fixgo.user;

import com.fixgo.partner.PartnerType;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String fullName, String phone, Role role, AppRole appRole,
                           AccountStatus status, Instant createdAt) {
    public static UserResponse from(User user, String phone, PartnerType partnerType) {
        return new UserResponse(user.getId(), user.getFullName(), phone, user.getRole(),
                AppRole.of(user.getRole(), partnerType), user.getStatus(), user.getCreatedAt());
    }
}
