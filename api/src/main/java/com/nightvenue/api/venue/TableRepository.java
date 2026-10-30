package com.nightvenue.api.venue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TableRepository {
    private final JdbcTemplate jdbc;

    public TableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<VenueTable> findByVenue(long venueId) {
        return jdbc.query("""
                SELECT id, venue_id, code, area, capacity, minimum_spend, status, is_active, version
                FROM venue_tables
                WHERE venue_id = ? AND is_active = true
                ORDER BY code, id
                """, TableRepository::map, venueId);
    }

    public Optional<VenueTable> findById(long id) {
        return jdbc.query("""
                SELECT id, venue_id, code, area, capacity, minimum_spend, status, is_active, version
                FROM venue_tables WHERE id = ?
                """, TableRepository::map, id).stream().findFirst();
    }

    public Optional<VenueTable> findByIdForUpdate(long id) {
        return jdbc.query("""
                SELECT id, venue_id, code, area, capacity, minimum_spend, status, is_active, version
                FROM venue_tables WHERE id = ? FOR UPDATE
                """, TableRepository::map, id).stream().findFirst();
    }

    public VenueTable create(
            long venueId,
            String code,
            String area,
            int capacity,
            long minimumSpend,
            String status) {
        return jdbc.queryForObject("""
                INSERT INTO venue_tables (venue_id, code, area, capacity, minimum_spend, status, is_active, version)
                VALUES (?, ?, ?, ?, ?, ?, true, 0)
                RETURNING id, venue_id, code, area, capacity, minimum_spend, status, is_active, version
                """, TableRepository::map, venueId, code, area, capacity, minimumSpend, status);
    }

    public Optional<VenueTable> update(
            long id,
            String code,
            String area,
            int capacity,
            long minimumSpend,
            String status) {
        return jdbc.query("""
                UPDATE venue_tables
                SET code = ?, area = ?, capacity = ?, minimum_spend = ?, status = ?, version = version + 1
                WHERE id = ? AND is_active = true
                RETURNING id, venue_id, code, area, capacity, minimum_spend, status, is_active, version
                """, TableRepository::map, code, area, capacity, minimumSpend, status, id)
                .stream().findFirst();
    }

    public void softDelete(long id) {
        jdbc.update("""
                UPDATE venue_tables
                SET is_active = false, version = version + 1
                WHERE id = ? AND is_active = true
                """, id);
    }

    private static VenueTable map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new VenueTable(
                rs.getLong("id"),
                rs.getLong("venue_id"),
                rs.getString("code"),
                rs.getString("area"),
                rs.getInt("capacity"),
                rs.getLong("minimum_spend"),
                rs.getString("status"),
                rs.getBoolean("is_active"),
                rs.getLong("version"));
    }

    public record VenueTable(
            long id,
            long venueId,
            String code,
            String area,
            int capacity,
            long minimumSpend,
            String status,
            boolean active,
            long version) {
    }
}
