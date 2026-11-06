package com.nightvenue.api.shift;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public class ShiftRepository {
    private static final String COLUMNS = """
            id, venue_id, cashier_id, business_date, status, opening_float, opened_at,
            forced_review_at, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public ShiftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CashierShift> findOpenShiftByCashier(long cashierId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cashier_shifts WHERE cashier_id = ? AND status = 'OPEN'",
                        ShiftRepository::map, cashierId)
                .stream().findFirst();
    }

    public Optional<CashierShift> lockOpenShiftByCashier(long cashierId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cashier_shifts WHERE cashier_id = ? AND status = 'OPEN' FOR UPDATE",
                        ShiftRepository::map, cashierId)
                .stream().findFirst();
    }

    public CashierShift insertOpenShift(long venueId, long cashierId, LocalDate businessDate,
                                        long openingFloat, Instant openedAt) {
        Timestamp timestamp = Timestamp.from(openedAt);
        String sql = """
                INSERT INTO cashier_shifts (
                    venue_id, cashier_id, business_date, status, opening_float, opened_at,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, 'OPEN', ?, ?, 0, ?, ?)
                RETURNING %s
                """.formatted(COLUMNS);
        return jdbc.queryForObject(sql, ShiftRepository::map, venueId, cashierId, java.sql.Date.valueOf(businessDate),
                openingFloat, timestamp, timestamp, timestamp);
    }

    private static CashierShift map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp forcedReviewAt = rs.getTimestamp("forced_review_at");
        return new CashierShift(rs.getLong("id"), rs.getLong("venue_id"), rs.getLong("cashier_id"),
                rs.getObject("business_date", LocalDate.class), CashierShiftStatus.valueOf(rs.getString("status")),
                rs.getLong("opening_float"), rs.getTimestamp("opened_at").toInstant(),
                forcedReviewAt == null ? null : forcedReviewAt.toInstant(), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
