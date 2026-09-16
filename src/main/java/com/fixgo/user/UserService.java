package com.fixgo.user;

import com.fixgo.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class UserService {
    private final UserRepository users;
    private final Clock clock;

    public UserService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(users.findById(id).orElseThrow(this::notFound));
    }

    @Transactional
    public UserResponse update(UUID id, UserController.UpdateProfileRequest request) {
        var user = users.lockById(id).orElseThrow(this::notFound);
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account is disabled.");
        }
        user.updateProfile(request.fullName(), request.phoneNumber(), clock.instant());
        return UserResponse.from(user);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist.");
    }
}
