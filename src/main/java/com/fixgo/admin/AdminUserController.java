package com.fixgo.admin;

import com.fixgo.user.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final AdminUserService users;

    public AdminUserController(AdminUserService users) { this.users = users; }

    @GetMapping
    public AdminUserService.UserPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return users.list(page, size);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) { return users.get(id); }

    @PatchMapping("/{id}/role")
    public UserResponse role(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        return users.changeRole(id, request.role());
    }

    @PatchMapping("/{id}/status")
    public UserResponse status(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return users.changeStatus(id, request.status());
    }

    public record ChangeRoleRequest(@NotNull Role role) { }
    public record ChangeStatusRequest(@NotNull AccountStatus status) { }
}
