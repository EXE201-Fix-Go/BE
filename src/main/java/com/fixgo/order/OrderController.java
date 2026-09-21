package com.fixgo.order;

import com.fixgo.common.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) { this.orders = orders; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDtos.OrderResponse create(Authentication auth, @Valid @RequestBody OrderDtos.CreateOrderRequest request) {
        return orders.create(Actor.of(auth), request);
    }

    @GetMapping
    public List<OrderDtos.OrderResponse> mine(Authentication auth) { return orders.listMine(Actor.of(auth)); }

    @GetMapping("/{id}")
    public OrderDtos.OrderResponse get(Authentication auth, @PathVariable UUID id) { return orders.get(Actor.of(auth), id); }

    @GetMapping("/{id}/status")
    public OrderDtos.OrderStatusResponse status(Authentication auth, @PathVariable UUID id) {
        return orders.getStatus(Actor.of(auth), id);
    }

    @PostMapping("/{id}/confirm")
    public OrderDtos.OrderResponse confirm(Authentication auth, @PathVariable UUID id) { return orders.confirm(Actor.of(auth), id); }

    @PostMapping("/{id}/cancel")
    public OrderDtos.OrderResponse cancel(Authentication auth, @PathVariable UUID id,
                                          @Valid @RequestBody(required = false) OrderDtos.CancelRequest request) {
        return orders.cancel(Actor.of(auth), id, request == null ? "Cancelled by user" : request.reason());
    }

    @PostMapping("/{id}/arrive")
    public OrderDtos.OrderResponse arrive(Authentication auth, @PathVariable UUID id) { return orders.arrive(Actor.of(auth), id); }

    @PostMapping("/{id}/check")
    public OrderDtos.OrderResponse check(Authentication auth, @PathVariable UUID id) { return orders.startChecking(Actor.of(auth), id); }

    @PostMapping("/{id}/pause")
    public OrderDtos.OrderResponse pause(Authentication auth, @PathVariable UUID id,
                                         @Valid @RequestBody(required = false) PauseRequest request) {
        return orders.pause(Actor.of(auth), id, request == null ? null : request.note());
    }

    @PostMapping("/{id}/resume")
    public OrderDtos.OrderResponse resume(Authentication auth, @PathVariable UUID id) { return orders.resume(Actor.of(auth), id); }

    @PostMapping("/{id}/complete")
    public OrderDtos.OrderResponse complete(Authentication auth, @PathVariable UUID id) { return orders.complete(Actor.of(auth), id); }

    public record PauseRequest(@Size(max = 300) String note) { }
}
