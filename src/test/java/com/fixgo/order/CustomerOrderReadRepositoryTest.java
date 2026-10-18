package com.fixgo.order;

import com.fixgo.module.order.dto.CustomerOrderReadDtos.HistoryItem;
import com.fixgo.module.order.enums.OrderStatus;
import com.fixgo.module.order.repository.CustomerOrderReadRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerOrderReadRepositoryTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final CustomerOrderReadRepository repository = new CustomerOrderReadRepository(jdbc);

    @Test void pageUsesOneBoundedQueryAndExtraRowInsteadOfCount() {
        var customerId = UUID.randomUUID();
        var items = IntStream.range(0, 21).mapToObj(i -> new HistoryItem(UUID.randomUUID(), "FG-" + i,
                OrderStatus.COMPLETED, "tire-patch", "Vá lốp", "Địa chỉ", Instant.now(), null, BigDecimal.TEN)).toList();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<HistoryItem>>any()))
                .thenReturn(items);
        var result = repository.history(customerId, 3, 20, OrderStatus.COMPLETED);
        assertThat(result.items()).hasSize(20);
        assertThat(result.hasNext()).isTrue();
        var sql = ArgumentCaptor.forClass(String.class);
        var params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), org.mockito.ArgumentMatchers.<RowMapper<HistoryItem>>any());
        verifyNoMoreInteractions(jdbc);
        assertThat(params.getValue().getValue("customerId")).isEqualTo(customerId);
        assertThat(params.getValue().getValue("limit")).isEqualTo(21);
        assertThat(params.getValue().getValue("offset")).isEqualTo(60L);
        assertThat(params.getValue().getValue("status")).isEqualTo("COMPLETED");
        assertThat(sql.getValue()).doesNotContain("order_status_history", "quote_items", "order_photos");
    }

    @Test void clampsPageSizeAndHandlesEmptyHistory() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<HistoryItem>>any()))
                .thenReturn(List.of());
        var result = repository.history(UUID.randomUUID(), -1, 10000, null);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.items()).isEmpty();
    }

    @Test void activeLookupExcludesEveryTerminalStateAndIsScopedToCustomer() {
        var customerId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of(orderId));
        assertThat(repository.activeId(customerId)).contains(orderId);
        var params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(contains("limit 1"), params.capture(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any());
        assertThat(params.getValue().getValue("customerId")).isEqualTo(customerId);
        assertThat(params.getValue().getValue("terminal")).isEqualTo(
                List.of("COMPLETED", "CANCELLED", "NO_PARTNER_FOUND", "EXPIRED"));
    }
}
