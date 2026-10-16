package com.fixgo.module.demobot.controller;

import com.fixgo.module.demobot.service.DemoBotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime kill-switch endpoints for the Demo Bot Mechanic ("Thợ Bot Tự Động").
 *
 * <p>Mapped under both {@code /api/v1/admin/demo-bot} (for Admin) and {@code /api/v1/demo-bot}
 * (for authenticated testers on the mobile app Profile/Settings screen) so testers can toggle
 * the bot in RAM immediately without restarting Render.
 */
@RestController
@RequestMapping({"/api/v1/admin/demo-bot", "/api/v1/demo-bot"})
public class DemoBotController {
    private final DemoBotService demoBotService;

    public DemoBotController(DemoBotService demoBotService) {
        this.demoBotService = demoBotService;
    }

    public record ToggleRequest(Boolean enabled, Integer delaySeconds) { }

    @GetMapping("/status")
    public DemoBotService.BotStatusResponse status() {
        return demoBotService.getStatus();
    }

    @PostMapping("/toggle")
    public DemoBotService.BotStatusResponse toggle(@RequestBody(required = false) ToggleRequest request) {
        if (request != null && request.enabled() != null) {
            return demoBotService.setEnabled(request.enabled(), request.delaySeconds());
        }
        return demoBotService.toggle();
    }
}
