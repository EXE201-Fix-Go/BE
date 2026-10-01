package com.fixgo.module.iam.entity;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.repository.*;
import com.fixgo.module.iam.service.*;


import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** app_users — no password column by design (RB-01 / C-06). */
@Entity
@Table(name = "app_users")
public class User {
    @Id
    private UUID id;
    @Column(name = "full_name", length = 100)
    private String fullName;
    @Column(length = 254)
    private String email;
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;
    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() { }

    public User(Role role, String fullName, Instant now) {
        this.id = UUID.randomUUID();
        this.role = role;
        this.fullName = fullName == null || fullName.isBlank() ? null : fullName.strip();
        this.status = AccountStatus.ACTIVE;
        this.createdAt = now;
    }

    public boolean isActive() { return status == AccountStatus.ACTIVE; }
    public void recordLogin(Instant now) { lastLoginAt = now; }
    public void rename(String fullName) { this.fullName = fullName == null ? null : fullName.strip(); }
    public void updateProfile(String fullName, String email, LocalDate dateOfBirth, String avatarUrl) {
        this.fullName = fullName == null ? null : fullName.strip();
        this.email = normalize(email);
        this.dateOfBirth = dateOfBirth;
        this.avatarUrl = normalize(avatarUrl);
    }
    public void changeRole(Role role) { this.role = role; }
    public void changeStatus(AccountStatus status) { this.status = status; }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getAvatarUrl() { return avatarUrl; }
    public Role getRole() { return role; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }
}
