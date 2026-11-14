package com.nightvenue.api.pos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    public Optional<Order> findOrderForUpdate(long id) {
        return jdbc.query("""
                SELECT id, venue_id, order_number, table_id, customer_id, cashier_id, business_date, status,
                       subtotal, discount_amount, service_charge_amount, tax_amount, total_due, paid_total,
                       minimum_spend_snapshot, opened_at, closed_at, version
                FROM orders
                WHERE id = ?
                FOR UPDATE
                """, OrderRepository::mapOrder, id).stream().findFirst();
    }

    public int voidIfOpen(long id, Instant now) {
        return jdbc.update("""
                UPDATE orders SET status = 'VOIDED', closed_at = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND status = 'OPEN'
                """, Timestamp.from(now), Timestamp.from(now), id);
    }

    public int serveIfOrdered(long orderId, long itemId, Instant now) {
        return jdbc.update("""
                UPDATE order_items SET status = 'SERVED', updated_at = ?
                WHERE id = ? AND order_id = ? AND status = 'ORDERED'
                """, Timestamp.from(now), itemId, orderId);
    }

    public Optional<VenueRates> findVenueRates(long venueId) {
        return jdbc.query("""
                SELECT tax_rate_bps, service_charge_bps
                FROM venues
                WHERE id = ?
                """, (rs, row) -> new VenueRates(
                rs.getInt("tax_rate_bps"), rs.getInt("service_charge_bps")), venueId)
                .stream().findFirst();
    }

    public Optional<Long> findBarLocationId(long venueId) {
        return jdbc.query("""
                SELECT id
                FROM stock_locations
                WHERE venue_id = ? AND type = 'BAR'
                """, (rs, row) -> rs.getLong("id"), venueId)
                .stream().findFirst();
    }

    public Optional<Long> findInventoryItemId(long locationId, long ingredientId) {
        return jdbc.query("""
                SELECT id
                FROM inventory_items
                WHERE location_id = ? AND ingredient_id = ?
                """, (rs, row) -> rs.getLong("id"), locationId, ingredientId)
                .stream().findFirst();
    }

    public OrderItem insertOrderItem(
            long orderId,
            long productId,
            String productNameSnapshot,
            long unitPriceSnapshot,
            int quantity,
            long lineTotal,
            Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbc.queryForObject("""
                INSERT INTO order_items (
                    order_id, product_id, item_type, product_name_snapshot,
                    unit_price_snapshot, quantity, line_total, status, created_at, updated_at
                )
                VALUES (?, ?, 'PRODUCT', ?, ?, ?, ?, 'ORDERED', ?, ?)
                RETURNING id, order_id, product_id, item_type, product_name_snapshot,
                          unit_price_snapshot, quantity, line_total, status, created_at, updated_at
                """, OrderRepository::mapOrderItem,
                orderId, productId, productNameSnapshot, unitPriceSnapshot, quantity, lineTotal,
                timestamp, timestamp);
    }

    public List<OrderItem> findOrderItems(long orderId) {
        return jdbc.query("""
                SELECT id, order_id, product_id, item_type, product_name_snapshot,
                       unit_price_snapshot, quantity, line_total, status, created_at, updated_at
                FROM order_items
                WHERE order_id = ?
                ORDER BY id ASC
                """, OrderRepository::mapOrderItem, orderId);
    }

    public BigDecimal calculateSubtotal(long orderId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(line_total), 0)
                FROM order_items
                WHERE order_id = ? AND status != 'VOIDED'
                """, BigDecimal.class, orderId);
    }

    public void updateOrderTotals(
            long id,
            long subtotal,
            long serviceChargeAmount,
            long taxAmount,
            long totalDue,
            Instant updatedAt) {
        jdbc.update("""
                UPDATE orders
                SET subtotal = ?, service_charge_amount = ?, tax_amount = ?, total_due = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ?
                """,
                subtotal, serviceChargeAmount, taxAmount, totalDue, Timestamp.from(updatedAt), id);
    }

    /**
     * Payment settlement (M2, additive). CALLER MUST HOLD the order row lock and
     * must require exactly one affected row. closedAt is set only when the order
     * becomes PAID; partial payments keep closed_at null.
     */
    public int updatePaidTotalAndStatus(long id, long paidTotal, String status,
                                        Instant closedAt, Instant updatedAt) {
        return jdbc.update("""
                UPDATE orders
                SET paid_total = ?, status = ?, closed_at = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND status IN ('OPEN', 'PARTIALLY_PAID')
                """, paidTotal, status, closedAt == null ? null : Timestamp.from(closedAt),
                Timestamp.from(updatedAt), id);
    }

    public void applySettlement(Order locked, long paidTotal, long paymentId,
                                OrderAuditWriter audit, Instant now) {
        if ("VOIDED".equals(locked.status())) {
            audit.anomaly(locked.venueId(), "PAYMENT_ON_CLOSED_ORDER", "PAYMENT", paymentId,
                    java.util.Map.of("order_id", locked.id()), now);
            return;
        }
        if (!"OPEN".equals(locked.status()) && !"PARTIALLY_PAID".equals(locked.status())) return;
        boolean fullyPaid = paidTotal >= locked.totalDue();
        int changed = updatePaidTotalAndStatus(locked.id(), paidTotal,
                fullyPaid ? "PAID" : "PARTIALLY_PAID", fullyPaid ? now : null, now);
        if (changed != 1) throw new IllegalStateException("Expected to update one payable order");
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

    private static OrderItem mapOrderItem(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new OrderItem(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("product_id"),
                rs.getString("item_type"),
                rs.getString("product_name_snapshot"),
                rs.getLong("unit_price_snapshot"),
                rs.getInt("quantity"),
                rs.getLong("line_total"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public record VenueContext(String timezone, int businessDayCutoffHour) {
    }

    public record VenueRates(int taxRateBps, int serviceChargeBps) {
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

    public record OrderItem(
            long id,
            long orderId,
            long productId,
            String itemType,
            String productNameSnapshot,
            long unitPriceSnapshot,
            int quantity,
            long lineTotal,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }
}
