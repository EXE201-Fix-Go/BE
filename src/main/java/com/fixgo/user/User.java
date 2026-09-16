package com.fixgo.user;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class User {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;
    private Instant lockedUntil;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected User() { }

    public User(String email, String passwordHash, String fullName, String phoneNumber, Role role, Instant now) {
        this.id = UUID.randomUUID();
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName.strip();
        this.phoneNumber = phoneNumber;
        this.role = role;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static String normalizeEmail(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    public boolean canLogin(Instant now) {
        return status == AccountStatus.ACTIVE && (lockedUntil == null || !lockedUntil.isAfter(now));
    }

    public void recordLoginFailure(Instant now, int threshold, Duration duration) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            failedLoginAttempts = 0;
            lockedUntil = null;
        }
        failedLoginAttempts++;
        if (failedLoginAttempts >= threshold) {
            lockedUntil = now.plus(duration);
        }
        updatedAt = now;
    }

    public void resetLoginFailures(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    public void updateProfile(String fullName, String phoneNumber, Instant now) {
        this.fullName = fullName.strip();
        this.phoneNumber = phoneNumber;
        this.updatedAt = now;
    }

    public void changePassword(String hash, Instant now) {
        passwordHash = hash;
        resetLoginFailures(now);
    }

    public void changeRole(Role role, Instant now) { this.role = role; this.updatedAt = now; }
    public void changeStatus(AccountStatus status, Instant now) { this.status = status; this.updatedAt = now; }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public String getPhoneNumber() { return phoneNumber; }
    public Role getRole() { return role; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
