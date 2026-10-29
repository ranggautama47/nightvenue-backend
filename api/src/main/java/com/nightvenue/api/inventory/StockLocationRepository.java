package com.nightvenue.api.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class StockLocationRepository {
    private final JdbcTemplate jdbc;

    public StockLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StockLocation> findByVenue(long venueId) {
        return jdbc.query("""
                SELECT id, venue_id, name, type
                FROM stock_locations
                WHERE venue_id = ?
                ORDER BY name, id
                """, StockLocationRepository::mapLocation, venueId);
    }

    public Optional<StockLocation> findById(long id) {
        return jdbc.query("""
                SELECT id, venue_id, name, type
                FROM stock_locations
                WHERE id = ?
                """, StockLocationRepository::mapLocation, id).stream().findFirst();
    }

    public Optional<StockLocation> findByIdForUpdate(long id) {
        return jdbc.query("""
                SELECT id, venue_id, name, type
                FROM stock_locations
                WHERE id = ?
                FOR UPDATE
                """, StockLocationRepository::mapLocation, id).stream().findFirst();
    }

    public boolean activeVenueExists(long venueId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM venues WHERE id = ? AND is_active = true)
                """, Boolean.class, venueId);
        return Boolean.TRUE.equals(exists);
    }

    public StockLocation create(long venueId, String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO stock_locations (venue_id, name, type)
                VALUES (?, ?, ?)
                RETURNING id, venue_id, name, type
                """, StockLocationRepository::mapLocation, venueId, name, type);
    }

    public boolean hasInventory(long locationId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM inventory_items WHERE location_id = ?)
                """, Boolean.class, locationId);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<StockLocation> update(long id, String name, String type) {
        return jdbc.query("""
                UPDATE stock_locations
                SET name = ?, type = ?
                WHERE id = ?
                RETURNING id, venue_id, name, type
                """, StockLocationRepository::mapLocation, name, type, id).stream().findFirst();
    }

    private static StockLocation mapLocation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StockLocation(
                rs.getLong("id"), rs.getLong("venue_id"), rs.getString("name"), rs.getString("type"));
    }

    public record StockLocation(long id, long venueId, String name, String type) {
    }
}
