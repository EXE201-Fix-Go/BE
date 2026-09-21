package com.fixgo.admin;

import com.fixgo.auth.UserDeviceRepository;
import com.fixgo.common.ApiException;
import com.fixgo.user.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class AdminUserService {
    private final UserRepository users;
    private final UserService userService;
    private final UserDeviceRepository devices;
    private final Clock clock;

    public AdminUserService(UserRepository users, UserService userService, UserDeviceRepository devices, Clock clock) {
        this.users = users;
        this.userService = userService;
        this.devices = devices;
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
        if (user.getStatus() != status) {
            user.changeStatus(status);
            if (status == AccountStatus.LOCKED) devices.revokeAllForUser(id, clock.instant(), adminId);
        }
        return userService.toResponse(user);
    }

    public record UserPage(List<UserResponse> items, int page, int size, long totalElements, int totalPages) { }
}
