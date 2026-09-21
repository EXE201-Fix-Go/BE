package com.fixgo.payment;

import com.fixgo.common.Actor;
import com.fixgo.order.OrderDtos;
import com.fixgo.order.OrderService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/payment")
public class PaymentController {
    private final PaymentService payments;
    private final OrderService orders;

    public PaymentController(PaymentService payments, OrderService orders) {
        this.payments = payments;
        this.orders = orders;
    }

    /** "Đã thanh toán" — visibility of the order doubles as the permission check (customer, partner or admin). */
    @PostMapping("/confirm")
    public OrderDtos.OrderResponse confirm(Authentication auth, @PathVariable UUID orderId) {
        var actor = Actor.of(auth);
        orders.get(actor, orderId);
        payments.confirm(actor, orderId);
        return orders.get(actor, orderId);
    }
}
