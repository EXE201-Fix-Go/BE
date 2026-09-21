package com.fixgo.user;

import com.fixgo.common.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @GetMapping
    public UserResponse me(Authentication auth) {
        return users.get(Actor.of(auth).userId());
    }

    @PatchMapping
    public UserResponse update(Authentication auth, @Valid @RequestBody UpdateProfileRequest request) {
        return users.rename(Actor.of(auth).userId(), request.fullName());
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 100) String fullName) { }
}
