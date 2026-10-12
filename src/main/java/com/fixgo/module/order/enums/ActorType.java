package com.fixgo.module.order.enums;
import com.fixgo.module.order.entity.*;
import com.fixgo.module.order.enums.*;
import com.fixgo.module.order.dto.*;
import com.fixgo.module.order.repository.*;
import com.fixgo.module.order.service.*;


import com.fixgo.module.iam.enums.Role;

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
