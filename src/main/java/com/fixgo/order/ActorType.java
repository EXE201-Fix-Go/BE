package com.fixgo.order;

import com.fixgo.user.Role;

/** Who changed an order: mirrors order_status_history.actor_type and rescue_orders.cancellation_source. */
public enum ActorType {
    CUSTOMER, PARTNER, ADMIN, SYSTEM;

    public static ActorType of(Role role) {
        return switch (role) {
            case CUSTOMER -> CUSTOMER;
            case PARTNER -> PARTNER;
            case ADMIN -> ADMIN;
        };
    }
}
