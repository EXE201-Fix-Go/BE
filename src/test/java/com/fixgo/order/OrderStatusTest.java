package com.fixgo.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ERD §7.1 transition table, pinned so a refactor cannot silently widen or narrow it. */
class OrderStatusTest {
    @Test
    void exactlyFourteenStates() {
        assertThat(OrderStatus.values()).hasSize(14);
    }

    @Test
    void happyPathIsLegal() {
        OrderStatus[] path = {OrderStatus.PENDING_CONFIRMATION, OrderStatus.REQUESTED, OrderStatus.ASSIGNED,
                OrderStatus.ARRIVED, OrderStatus.CHECKING, OrderStatus.WAITING_FOR_APPROVAL, OrderStatus.APPROVED,
                OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED};
        for (int i = 1; i < path.length; i++) {
            assertThat(path[i - 1].canTransitionTo(path[i])).as(path[i - 1] + " -> " + path[i]).isTrue();
        }
    }

    @Test
    void terminalStatesHaveNoExits() {
        for (var s : new OrderStatus[] {OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.NO_PARTNER_FOUND,
                OrderStatus.EXPIRED}) {
            assertThat(s.isTerminal()).isTrue();
            for (var t : OrderStatus.values()) assertThat(s.canTransitionTo(t)).isFalse();
        }
    }

    @Test
    void approvedCannotBeCancelledAndInProgressCannotBeCancelledDirectly() {
        assertThat(OrderStatus.APPROVED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.IN_PROGRESS.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.PAUSED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.ADDITIONAL_QUOTE.canTransitionTo(OrderStatus.IN_PROGRESS)).isTrue();
    }
}
