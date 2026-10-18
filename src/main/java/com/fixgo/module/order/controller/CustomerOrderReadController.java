package com.fixgo.module.order.controller;

import com.fixgo.module.iam.enums.Role;
import com.fixgo.module.order.dto.CustomerOrderReadDtos.HistoryPage;
import com.fixgo.module.order.dto.OrderDtos.OrderResponse;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.order.repository.CustomerOrderReadRepository;
import com.fixgo.module.order.service.OrderService;
import com.fixgo.shared.util.Actor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/orders")
public class CustomerOrderReadController {
    private final CustomerOrderReadRepository reads;
    private final OrderService orders;

    public CustomerOrderReadController(CustomerOrderReadRepository reads, OrderService orders) {
        this.reads = reads;
        this.orders = orders;
    }

    @GetMapping("/history")
    public HistoryPage history(Authentication auth,
                               @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
                               @RequestParam(required = false) OrderStatus status) {
        return reads.history(customer(auth).userId(), page, size, status);
    }

    @GetMapping("/active")
    public ResponseEntity<OrderResponse> active(Authentication auth) {
        var actor = customer(auth);
        return reads.activeId(actor.userId())
                .map(id -> ResponseEntity.ok(orders.get(actor, id)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private Actor customer(Authentication auth) {
        var actor = Actor.of(auth);
        if (!actor.is(Role.CUSTOMER)) throw OrderService.forbidden();
        return actor;
    }
}
