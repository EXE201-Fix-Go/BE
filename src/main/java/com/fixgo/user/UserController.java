package com.fixgo.user;

import com.fixgo.auth.AuthDtos;
import com.fixgo.auth.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {
    private final UserService users;
    private final AuthService auth;

    public UserController(UserService users, AuthService auth) { this.users = users; this.auth = auth; }

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return users.get(UUID.fromString(jwt.getSubject()));
    }

    @PatchMapping
    public UserResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return users.update(UUID.fromString(jwt.getSubject()), request);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody AuthDtos.ChangePasswordRequest request) {
        auth.changePassword(UUID.fromString(jwt.getSubject()), request);
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 100) String fullName,
            @Pattern(regexp = "^(0[35789][0-9]{8}|\\+[1-9][0-9]{7,14})$") String phoneNumber) {
        public UpdateProfileRequest { fullName = fullName == null ? null : fullName.strip(); }
    }
}
