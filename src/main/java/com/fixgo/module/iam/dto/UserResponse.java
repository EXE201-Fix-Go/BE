package com.fixgo.module.iam.dto;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.repository.*;
import com.fixgo.module.iam.service.*;


import com.fixgo.module.partner.enums.PartnerType;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String fullName, String phone, Role role, AppRole appRole,
                           AccountStatus status, Instant createdAt) {
    public static UserResponse from(User user, String phone, PartnerType partnerType) {
        return new UserResponse(user.getId(), user.getFullName(), phone, user.getRole(),
                AppRole.of(user.getRole(), partnerType), user.getStatus(), user.getCreatedAt());
    }
}
