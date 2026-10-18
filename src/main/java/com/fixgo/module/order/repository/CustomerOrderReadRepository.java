package com.fixgo.module.order.repository;

import com.fixgo.module.order.dto.CustomerOrderReadDtos.HistoryItem;
import com.fixgo.module.order.dto.CustomerOrderReadDtos.HistoryPage;
import com.fixgo.module.order.enums.OrderStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** Bounded read models: one SQL round trip per history page, with no JPA collection loading. */
@Repository
public class CustomerOrderReadRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CustomerOrderReadRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public HistoryPage history(UUID customerId, int page, int size, OrderStatus status) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 50));
        var params = new MapSqlParameterSource("customerId", customerId)
                .addValue("limit", safeSize + 1)
                .addValue("offset", (long) safePage * safeSize);
        String filter = "";
        if (status != null) {
            filter = " and status = :status";
            params.addValue("status", status.name());
        }
        // Limit orders BEFORE joining details. Lateral subqueries read at most one row per card.
        String sql = """
                with page_orders as (
                    select id, order_code, status, requested_service_id, pickup_address_text,
                           created_at, call_out_fee_snapshot
                    from rescue_orders
                    where customer_id = :customerId
                """ + filter + """
                    order by created_at desc, id desc
                    limit :limit offset :offset
                )
                select o.id, o.order_code, o.status, s.code as service_id, s.name as service_name,
                       o.pickup_address_text as address_text, o.created_at, u.full_name as partner_name,
                       case when q.status = 'APPROVED' then q.total_amount
                            else coalesce(p.amount, o.call_out_fee_snapshot) end as total_amount
                from page_orders o
                left join services s on s.id = o.requested_service_id
                left join lateral (
                    select partner_id from order_assignments
                    where order_id = o.id and status = 'ACCEPTED'
                    order by accepted_at desc, id desc limit 1
                ) a on true
                left join app_users u on u.id = a.partner_id
                left join lateral (
                    select status, total_amount from quotes where order_id = o.id
                    order by revision_no desc limit 1
                ) q on true
                left join lateral (
                    select amount from payments where order_id = o.id
                    order by created_at desc, id desc limit 1
                ) p on true
                order by o.created_at desc, o.id desc
                """;
        var items = jdbc.query(sql, params, (rs, row) -> new HistoryItem(
                rs.getObject("id", UUID.class), rs.getString("order_code"),
                OrderStatus.valueOf(rs.getString("status")), rs.getString("service_id"),
                rs.getString("service_name"), rs.getString("address_text"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("partner_name"),
                rs.getBigDecimal("total_amount")));
        boolean hasNext = items.size() > safeSize;
        return new HistoryPage(java.util.List.copyOf(items.subList(0, Math.min(items.size(), safeSize))),
                safePage, safeSize, hasNext);
    }

    public Optional<UUID> activeId(UUID customerId) {
        var terminal = Arrays.stream(OrderStatus.values()).filter(OrderStatus::isTerminal)
                .map(Enum::name).toList();
        return jdbc.query("""
                select id from rescue_orders
                where customer_id = :customerId and status not in (:terminal)
                order by created_at desc, id desc limit 1
                """, new MapSqlParameterSource("customerId", customerId).addValue("terminal", terminal),
                (rs, row) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }
}
