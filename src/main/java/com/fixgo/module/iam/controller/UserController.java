package com.fixgo.module.iam.controller;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.repository.*;
import com.fixgo.module.iam.service.*;


import com.fixgo.shared.util.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

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
        return users.updateProfile(Actor.of(auth).userId(), request.fullName(), request.email(),
                request.dateOfBirth(), request.avatarUrl());
    }

    public record UpdateProfileRequest(
            @NotBlank @Size(max = 100) String fullName,
            @Email @Size(max = 254) String email,
            @Past LocalDate dateOfBirth,
            @Size(max = 2000) String avatarUrl) { }
}
