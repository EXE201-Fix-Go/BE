package com.fixgo.module.admin.controller;

import com.fixgo.module.admin.service.AdminKycService;
import com.fixgo.shared.util.Actor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** KYC documents for the admin verification queue. The files are streamed through the backend, never linked. */
@RestController
@RequestMapping("/api/v1/admin/partners/{partnerId}/documents")
@PreAuthorize("hasRole('ADMIN')")
public class AdminKycController {
    private final AdminKycService service;

    public AdminKycController(AdminKycService service) { this.service = service; }

    @GetMapping
    public List<AdminKycService.DocumentRow> list(Authentication auth, @PathVariable UUID partnerId) {
        return service.list(Actor.of(auth), partnerId);
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(Authentication auth, @PathVariable UUID partnerId, @PathVariable UUID documentId) {
        var file = service.content(Actor.of(auth), partnerId, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }
}
