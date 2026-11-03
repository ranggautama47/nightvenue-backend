package com.nightvenue.api.pos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VenueContext> findVenueContext(long venueId) {
        return jdbc.query("""
                SELECT timezone, business_day_cutoff_hour
                FROM venues
                WHERE id = ? AND is_active = true
                """, (rs, row) -> new VenueContext(
                rs.getString("timezone"), rs.getInt("business_day_cutoff_hour")), venueId)
                .stream().findFirst();
    }

    public boolean customerExists(long customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM customers WHERE id = ?)", Boolean.class, customerId));
    }

    public Order insert(
            long venueId,
            Long tableId,
            Long customerId,
            long cashierId,
            LocalDate businessDate,
            long minimumSpendSnapshot,
            Instant openedAt) {
        Timestamp openedTimestamp = Timestamp.from(openedAt);
        return jdbc.queryForObject("""
                WITH next_order_number AS (
                    SELECT nextval('order_number_seq') AS sequence_value
                )
                INSERT INTO orders (
                    venue_id, order_number, table_id, customer_id, cashier_id, business_date, status,
                    subtotal, discount_amount, service_charge_amount, tax_amount, total_due, paid_total,
                    minimum_spend_snapshot, opened_at, created_at, updated_at, version
                )
                SELECT ?, 'NV-' || to_char(?::date, 'YYYYMMDD') || '-'
                       || repeat('0', greatest(6 - length(sequence_value::text), 0))
                       || sequence_value::text,
                       ?::bigint, ?::bigint, ?, ?::date, 'OPEN',
                       0, 0, 0, 0, 0, 0, ?, ?, ?, ?, 0
                FROM next_order_number
                RETURNING id, venue_id, order_number, table_id, customer_id, cashier_id, business_date, status,
                          subtotal, discount_amount, service_charge_amount, tax_amount, total_due, paid_total,
                          minimum_spend_snapshot, opened_at, closed_at, version
                """, OrderRepository::mapOrder,
                venueId, Date.valueOf(businessDate), tableId, customerId, cashierId, Date.valueOf(businessDate),
                minimumSpendSnapshot, openedTimestamp, openedTimestamp, openedTimestamp);
    }

    public Optional<Order> findById(long id) {
        return jdbc.query("""
                SELECT id, venue_id, order_number, table_id, customer_id, cashier_id, business_date, status,
                       subtotal, discount_amount, service_charge_amount, tax_amount, total_due, paid_total,
                       minimum_spend_snapshot, opened_at, closed_at, version
                FROM orders
                WHERE id = ?
                """, OrderRepository::mapOrder, id).stream().findFirst();
    }

    private static Order mapOrder(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Order(
                rs.getLong("id"),
                rs.getLong("venue_id"),
                rs.getString("order_number"),
                rs.getObject("table_id", Long.class),
                rs.getObject("customer_id", Long.class),
                rs.getLong("cashier_id"),
                rs.getObject("business_date", LocalDate.class),
                rs.getString("status"),
                rs.getLong("subtotal"),
                rs.getLong("discount_amount"),
                rs.getLong("service_charge_amount"),
                rs.getLong("tax_amount"),
                rs.getLong("total_due"),
                rs.getLong("paid_total"),
                rs.getLong("minimum_spend_snapshot"),
                rs.getTimestamp("opened_at").toInstant(),
                closedAt == null ? null : closedAt.toInstant(),
                rs.getLong("version"));
    }

    public record VenueContext(String timezone, int businessDayCutoffHour) {
    }

    public record Order(
            long id,
            long venueId,
            String orderNumber,
            Long tableId,
            Long customerId,
            long cashierId,
            LocalDate businessDate,
            String status,
            long subtotal,
            long discountAmount,
            long serviceChargeAmount,
            long taxAmount,
            long totalDue,
            long paidTotal,
            long minimumSpendSnapshot,
            Instant openedAt,
            Instant closedAt,
            long version) {
    }
}
