package com.nightvenue.api.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class InventoryRepository {
    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<InventoryItem> findByVenue(long venueId, Long locationId, Long ingredientId) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.id, i.location_id, l.name AS location_name, l.type AS location_type,
                       l.venue_id, i.ingredient_id, g.sku AS ingredient_sku, g.name AS ingredient_name,
                       g.unit, i.quantity, i.reorder_level, i.version
                FROM inventory_items i
                JOIN stock_locations l ON l.id = i.location_id
                JOIN ingredients g ON g.id = i.ingredient_id
                WHERE l.venue_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(venueId);
        if (locationId != null) {
            sql.append(" AND l.id = ?");
            parameters.add(locationId);
        }
        if (ingredientId != null) {
            sql.append(" AND i.ingredient_id = ?");
            parameters.add(ingredientId);
        }
        sql.append(" ORDER BY i.id");
        return jdbc.query(sql.toString(), InventoryRepository::mapItem, parameters.toArray());
    }

    public Optional<InventoryItem> findItem(long id) {
        return jdbc.query("""
                SELECT i.id, i.location_id, l.name AS location_name, l.type AS location_type,
                       l.venue_id, i.ingredient_id, g.sku AS ingredient_sku, g.name AS ingredient_name,
                       g.unit, i.quantity, i.reorder_level, i.version
                FROM inventory_items i
                JOIN stock_locations l ON l.id = i.location_id
                JOIN ingredients g ON g.id = i.ingredient_id
                WHERE i.id = ?
                """, InventoryRepository::mapItem, id).stream().findFirst();
    }

    public InventoryItem create(long locationId, long ingredientId, BigDecimal reorderLevel, Instant createdAt) {
        long id = jdbc.queryForObject("""
                INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version, updated_at)
                VALUES (?, ?, 0, ?, 0, ?)
                RETURNING id
                """, Long.class, locationId, ingredientId, reorderLevel, Timestamp.from(createdAt));
        return findItem(id).orElseThrow();
    }

    public Optional<LocationVenue> findLocationVenueForUpdate(long locationId) {
        return jdbc.query("""
                SELECT l.id, l.venue_id
                FROM stock_locations l
                WHERE l.id = ?
                FOR UPDATE
                """, (rs, row) -> new LocationVenue(rs.getLong("id"), rs.getLong("venue_id")),
                locationId).stream().findFirst();
    }

    public boolean lockActiveIngredient(long ingredientId) {
        return !jdbc.query("""
                SELECT id FROM ingredients WHERE id = ? AND is_active = true FOR SHARE
                """, (rs, row) -> rs.getLong("id"), ingredientId).isEmpty();
    }

    public boolean inventoryItemExists(long locationId, long ingredientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM inventory_items WHERE location_id = ? AND ingredient_id = ?
                )
                """, Boolean.class, locationId, ingredientId);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<LockedInventoryItem> findForUpdate(long id) {
        return jdbc.query("""
                SELECT i.id, i.location_id, l.venue_id, i.quantity, i.version,
                       v.timezone, v.business_day_cutoff_hour
                FROM inventory_items i
                JOIN stock_locations l ON l.id = i.location_id
                JOIN venues v ON v.id = l.venue_id
                WHERE i.id = ?
                FOR UPDATE OF i
                """, (rs, row) -> new LockedInventoryItem(
                rs.getLong("id"), rs.getLong("location_id"), rs.getLong("venue_id"),
                rs.getBigDecimal("quantity"), rs.getLong("version"),
                rs.getString("timezone"), rs.getInt("business_day_cutoff_hour")), id)
                .stream().findFirst();
    }

    public UpdateResult updateQuantity(long id, BigDecimal quantity, Instant updatedAt) {
        return jdbc.queryForObject("""
                UPDATE inventory_items
                SET quantity = ?, version = version + 1, updated_at = ?
                WHERE id = ?
                RETURNING quantity, version
                """, (rs, row) -> new UpdateResult(rs.getBigDecimal("quantity"), rs.getLong("version")),
                quantity, Timestamp.from(updatedAt), id);
    }

    public long insertTransaction(
            long itemId,
            String type,
            BigDecimal delta,
            BigDecimal balanceAfter,
            LocalDate businessDate,
            String referenceType,
            Long referenceId,
            String reason,
            Long actorUserId,
            Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_transactions
                    (inventory_item_id, type, delta, balance_after, business_date,
                     reference_type, reference_id, reason, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, itemId, type, delta, balanceAfter, businessDate,
                referenceType, referenceId, reason, actorUserId, Timestamp.from(createdAt));
    }

    public List<InventoryTransaction> transactions(long itemId, int limit) {
        return jdbc.query("""
                SELECT id, inventory_item_id, type, delta, balance_after, business_date,
                       reference_type, reference_id, reason, created_by, created_at
                FROM inventory_transactions
                WHERE inventory_item_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, (rs, row) -> new InventoryTransaction(
                rs.getLong("id"), rs.getLong("inventory_item_id"), rs.getString("type"),
                rs.getBigDecimal("delta"), rs.getBigDecimal("balance_after"),
                rs.getObject("business_date", LocalDate.class), rs.getString("reference_type"),
                rs.getObject("reference_id", Long.class), rs.getString("reason"),
                rs.getObject("created_by", Long.class), rs.getTimestamp("created_at").toInstant()),
                itemId, limit);
    }

    public Optional<DeductedItem> deduct(long venueId, long locationId, long ingredientId,
                                         BigDecimal quantity, Instant updatedAt) {
        return jdbc.query("""
                UPDATE inventory_items i
                SET quantity = i.quantity - ?, version = i.version + 1, updated_at = ?
                WHERE i.id = (
                    SELECT ii.id
                    FROM inventory_items ii
                    JOIN stock_locations l ON l.id = ii.location_id
                    WHERE l.venue_id = ? AND l.id = ? AND ii.ingredient_id = ?
                )
                  AND i.quantity >= ?
                RETURNING i.id, i.quantity
                """, (rs, row) -> new DeductedItem(rs.getLong("id"), rs.getBigDecimal("quantity")),
                quantity, Timestamp.from(updatedAt), venueId, locationId, ingredientId, quantity)
                .stream().findFirst();
    }

    private static InventoryItem mapItem(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new InventoryItem(
                rs.getLong("id"), rs.getLong("location_id"), rs.getString("location_name"),
                rs.getString("location_type"), rs.getLong("venue_id"), rs.getLong("ingredient_id"),
                rs.getString("ingredient_sku"), rs.getString("ingredient_name"), rs.getString("unit"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("reorder_level"), rs.getLong("version"));
    }

    public record InventoryItem(
            long id, long locationId, String locationName, String locationType, long venueId,
            long ingredientId, String ingredientSku, String ingredientName, String unit,
            BigDecimal quantity, BigDecimal reorderLevel, long version) {
    }

    public record LocationVenue(long locationId, long venueId) {
    }

    public record LockedInventoryItem(
            long id, long locationId, long venueId, BigDecimal quantity, long version,
            String timezone, int cutoffHour) {
    }

    public record UpdateResult(BigDecimal quantity, long version) {
    }

    public record InventoryTransaction(
            long id, long inventoryItemId, String type, BigDecimal delta, BigDecimal balanceAfter,
            LocalDate businessDate, String referenceType, Long referenceId, String reason,
            Long createdBy, Instant createdAt) {
    }

    public record DeductedItem(long id, BigDecimal quantity) {
    }
}
