package com.fixgo.module.admin.controller;

import com.fixgo.module.admin.dto.AdminDtos;
import com.fixgo.module.admin.service.AdminDashboardService;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.partner.enums.VerificationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only endpoints behind the admin web page. SecurityConfig already restricts {@code /api/v1/admin/**} to
 * ADMIN; the annotation below repeats that at the method layer (NT-02), as {@link AdminUserController} does.
 * Account locking and KYC approval live in {@link AdminUserController}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminDashboardController {
    private final AdminDashboardService dashboard;

    public AdminDashboardController(AdminDashboardService dashboard) { this.dashboard = dashboard; }

    @GetMapping("/overview")
    public AdminDtos.OverviewResponse overview() { return dashboard.overview(); }

    /** KYC queue: pass {@code status=PENDING}; omit it to list every partner. */
    @GetMapping("/partners")
    public AdminDtos.PageResponse<AdminDtos.PartnerRow> partners(
            @RequestParam(required = false) VerificationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return dashboard.partners(status, page, size);
    }

    @GetMapping("/orders")
    public AdminDtos.PageResponse<AdminDtos.OrderRow> orders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return dashboard.orders(status, page, size);
    }
}
