package com.fixgo.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Exactly the 14 order states of ERD §7.1 and their legal transitions. SCREAMING_SNAKE_CASE by rule. */
public enum OrderStatus {
    PENDING_CONFIRMATION,
    REQUESTED,
    ASSIGNED,
    ARRIVED,
    CHECKING,
    WAITING_FOR_APPROVAL,
    APPROVED,
    IN_PROGRESS,
    ADDITIONAL_QUOTE,
    PAUSED,
    COMPLETED,
    CANCELLED,
    NO_PARTNER_FOUND,
    EXPIRED;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(PENDING_CONFIRMATION, EnumSet.of(REQUESTED, CANCELLED, EXPIRED)),
            Map.entry(REQUESTED, EnumSet.of(ASSIGNED, CANCELLED, NO_PARTNER_FOUND)),
            Map.entry(ASSIGNED, EnumSet.of(ARRIVED, CANCELLED)),
            Map.entry(ARRIVED, EnumSet.of(CHECKING, CANCELLED)),
            Map.entry(CHECKING, EnumSet.of(WAITING_FOR_APPROVAL, CANCELLED)),
            Map.entry(WAITING_FOR_APPROVAL, EnumSet.of(APPROVED, CANCELLED)),
            Map.entry(APPROVED, EnumSet.of(IN_PROGRESS)),
            Map.entry(IN_PROGRESS, EnumSet.of(ADDITIONAL_QUOTE, PAUSED, COMPLETED)),
            Map.entry(ADDITIONAL_QUOTE, EnumSet.of(IN_PROGRESS, CANCELLED)),
            Map.entry(PAUSED, EnumSet.of(IN_PROGRESS, COMPLETED, CANCELLED)),
            Map.entry(COMPLETED, EnumSet.noneOf(OrderStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(OrderStatus.class)),
            Map.entry(NO_PARTNER_FOUND, EnumSet.noneOf(OrderStatus.class)),
            Map.entry(EXPIRED, EnumSet.noneOf(OrderStatus.class)));

    public boolean canTransitionTo(OrderStatus next) { return TRANSITIONS.get(this).contains(next); }

    public boolean isTerminal() { return TRANSITIONS.get(this).isEmpty(); }

    /** RB-28: PENDING_CONFIRMATION and EXPIRED do not count toward completion rate. */
    public boolean countsTowardCompletion() { return this != PENDING_CONFIRMATION && this != EXPIRED; }
}
