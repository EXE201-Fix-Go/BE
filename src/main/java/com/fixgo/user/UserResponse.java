package com.fixgo.user;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String fullName, String phoneNumber,
                           Role role, AccountStatus status, Instant createdAt) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhoneNumber(),
                user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
