package com.fixgo.order;

import com.fixgo.module.order.controller.CustomerOrderReadController;
import com.fixgo.module.order.dto.CustomerOrderReadDtos.HistoryPage;
import com.fixgo.module.order.dto.OrderDtos.OrderResponse;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.order.repository.CustomerOrderReadRepository;
import com.fixgo.module.order.service.OrderService;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.util.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerOrderReadControllerTest {
    private final CustomerOrderReadRepository reads = mock(CustomerOrderReadRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final CustomerOrderReadController controller = new CustomerOrderReadController(reads, orders);
    private final UUID userId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), "unused",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test void historyAlwaysUsesAuthenticatedCustomer() {
        var page = new HistoryPage(List.of(), 2, 20, false);
        when(reads.history(userId, 2, 20, OrderStatus.COMPLETED)).thenReturn(page);
        assertThat(controller.history(auth("CUSTOMER"), 2, 20, OrderStatus.COMPLETED)).isSameAs(page);
        verify(reads).history(userId, 2, 20, OrderStatus.COMPLETED);
        verifyNoInteractions(orders);
    }

    @Test void otherRolesCannotReadCustomerListsOrActiveOrders() {
        for (String role : List.of("PARTNER", "ADMIN")) {
            assertThatThrownBy(() -> controller.history(auth(role), 0, 20, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
            assertThatThrownBy(() -> controller.active(auth(role))).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(reads, orders);
    }

    @Test void noActiveOrderReturns204WithoutFetchingHistory() {
        when(reads.activeId(userId)).thenReturn(Optional.empty());
        assertThat(controller.active(auth("CUSTOMER")).getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(orders);
    }

    @Test void activeOrderReusesExistingVisibilityRules() {
        var id = UUID.randomUUID();
        var response = mock(OrderResponse.class);
        when(reads.activeId(userId)).thenReturn(Optional.of(id));
        when(orders.get(any(Actor.class), eq(id))).thenReturn(response);
        assertThat(controller.active(auth("CUSTOMER")).getBody()).isSameAs(response);
        verify(orders).get(new Actor(userId, com.fixgo.module.iam.enums.Role.CUSTOMER), id);
    }
}
