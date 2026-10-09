package com.fixgo.module.admin.service;
import com.fixgo.module.admin.service.*;

import com.fixgo.module.iam.dto.UserResponse;

import com.fixgo.module.iam.repository.UserDeviceRepository;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.service.*;
import com.fixgo.module.iam.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fixgo.module.dispatch.repository.OrderAssignmentRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class AdminUserService {
    private final UserRepository users;
    private final UserService userService;
    private final UserDeviceRepository devices;
    private final OrderAssignmentRepository assignments;
    private final Clock clock;

    public AdminUserService(UserRepository users, UserService userService, UserDeviceRepository devices,
                            OrderAssignmentRepository assignments, Clock clock) {
        this.users = users;
        this.userService = userService;
        this.devices = devices;
        this.assignments = assignments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserPage list(int page, int size) {
        var result = users.findAllByOrderByCreatedAtDescIdAsc(PageRequest.of(page, size));
        return new UserPage(result.getContent().stream().map(userService::toResponse).toList(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return userService.toResponse(users.findById(id).orElseThrow(UserService::notFound));
    }

    /** Locking an account revokes every device so existing tokens stop working immediately. */
    @Transactional
    public UserResponse changeStatus(UUID adminId, UUID id, AccountStatus status) {
        var user = users.lockById(id).orElseThrow(UserService::notFound);
        if (user.getRole() == Role.ADMIN) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ACCOUNT_PROTECTED", "Admin accounts cannot be changed here.");
        }
        if (status == AccountStatus.LOCKED && user.getRole() == Role.PARTNER && assignments.hasActiveJob(id)) {
            // Locking would strand the customer: the admin must cancel or hand over the open order first.
            throw new ApiException(HttpStatus.CONFLICT, "PARTNER_HAS_ACTIVE_JOB",
                    "This partner has an open order. Cancel or reassign it before locking the account.");
        }
        if (user.getStatus() != status) {
            user.changeStatus(status);
            if (status == AccountStatus.LOCKED) devices.revokeAllForUser(id, clock.instant(), adminId);
        }
        return userService.toResponse(user);
    }

    public record UserPage(List<UserResponse> items, int page, int size, long totalElements, int totalPages) { }
}
