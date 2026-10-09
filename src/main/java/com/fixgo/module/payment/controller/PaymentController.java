package com.fixgo.module.payment.controller;
import com.fixgo.module.payment.entity.*;
import com.fixgo.module.payment.repository.*;
import com.fixgo.module.payment.service.*;


import com.fixgo.shared.util.Actor;
import com.fixgo.module.order.dto.OrderDtos;
import com.fixgo.module.order.service.OrderService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/payment")
public class PaymentController {
    private final OrderService orders;

    public PaymentController(OrderService orders) {
        this.orders = orders;
    }

    /** "Đã thanh toán" — the permission check and the write share one transaction in the service (NT-02). */
    @PostMapping("/confirm")
    public OrderDtos.OrderResponse confirm(Authentication auth, @PathVariable UUID orderId) {
        return orders.confirmPayment(Actor.of(auth), orderId);
    }
}
