package com.fixgo.admin;

import com.fixgo.auth.AuthSessionRepository;
import com.fixgo.common.ApiException;
import com.fixgo.user.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserService {
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final Clock clock;

    public AdminUserService(UserRepository users, AuthSessionRepository sessions, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserPage list(int page, int size) {
        var result = users.findAll(PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id"))));
        return new UserPage(result.getContent().stream().map(UserResponse::from).toList(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(users.findById(id).orElseThrow(this::notFound));
    }

    @Transactional
    public UserResponse changeRole(UUID id, Role role) {
        var user = users.lockById(id).orElseThrow(this::notFound);
        protectAdmin(user);
        if (role == Role.ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_ROLE_MANAGED_EXTERNALLY",
                    "Administrator accounts are provisioned through server configuration.");
        }
        if (user.getRole() != role) {
            user.changeRole(role, clock.instant());
            sessions.revokeAllForUser(id, clock.instant());
        }
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse changeStatus(UUID id, AccountStatus status) {
        var user = users.lockById(id).orElseThrow(this::notFound);
        protectAdmin(user);
        if (user.getStatus() != status) {
            user.changeStatus(status, clock.instant());
            sessions.revokeAllForUser(id, clock.instant());
        }
        return UserResponse.from(user);
    }

    private void protectAdmin(User user) {
        if (user.getRole() == Role.ADMIN) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ACCOUNT_PROTECTED",
                    "Administrator accounts cannot be modified through these endpoints.");
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist.");
    }

    public record UserPage(List<UserResponse> items, int page, int size, long totalElements, int totalPages) { }
}
