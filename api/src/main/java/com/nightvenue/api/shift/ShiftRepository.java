package com.nightvenue.api.shift;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    public Optional<CashierShift> findById(long shiftId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cashier_shifts WHERE id = ?", ShiftRepository::map, shiftId)
                .stream().findFirst();
    }

    public Optional<CashierShift> lockById(long shiftId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cashier_shifts WHERE id = ? FOR UPDATE", ShiftRepository::map, shiftId)
                .stream().findFirst();
    }

    public BigDecimal sumPaidCash(long shiftId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM payments
                WHERE shift_id = ? AND method = 'CASH' AND status = 'PAID'
                """, BigDecimal.class, shiftId);
    }

    public int markSubmitted(long shiftId, long expectedCash, long actualCash, long variance,
                             String note, Instant submittedAt) {
        Timestamp timestamp = Timestamp.from(submittedAt);
        return jdbc.update("""
                UPDATE cashier_shifts
                SET status = 'SUBMITTED', submitted_at = ?, expected_cash = ?, actual_cash = ?, variance = ?,
                    submit_note = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND status = 'OPEN'
                """, timestamp, expectedCash, actualCash, variance, note, timestamp, shiftId);
    }

    public Optional<ReviewData> reviewData(long shiftId) {
        return jdbc.query("""
                SELECT expected_cash, actual_cash, variance
                FROM cashier_shifts WHERE id = ?
                """, (rs, row) -> new ReviewData(
                (Long) rs.getObject("expected_cash"), (Long) rs.getObject("actual_cash"),
                (Long) rs.getObject("variance")), shiftId)
                .stream().findFirst();
    }

    public Optional<ShiftReviewView> findReviewView(long shiftId) {
        return jdbc.query("""
                SELECT id, venue_id, cashier_id, business_date, status, opening_float,
                       submitted_at, expected_cash, actual_cash, variance, submit_note,
                       review_note, reviewed_by, reviewed_at, forced_review_at
                FROM cashier_shifts WHERE id = ?
                """, (rs, row) -> {
            Timestamp submittedAt = rs.getTimestamp("submitted_at");
            Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
            Timestamp forcedReviewAt = rs.getTimestamp("forced_review_at");
            return new ShiftReviewView(rs.getLong("id"), rs.getLong("venue_id"), rs.getLong("cashier_id"),
                    rs.getObject("business_date", LocalDate.class), CashierShiftStatus.valueOf(rs.getString("status")),
                    rs.getLong("opening_float"), submittedAt == null ? null : submittedAt.toInstant(),
                    rs.getLong("expected_cash"), rs.getLong("actual_cash"), rs.getLong("variance"),
                    rs.getString("submit_note"), rs.getString("review_note"), rs.getLong("reviewed_by"),
                    reviewedAt == null ? null : reviewedAt.toInstant(),
                    forcedReviewAt == null ? null : forcedReviewAt.toInstant());
        }, shiftId).stream().findFirst();
    }

    public int reconcileSubmitted(long shiftId, long reviewerId, String reviewNote, Instant reviewedAt) {
        Timestamp timestamp = Timestamp.from(reviewedAt);
        return jdbc.update("""
                UPDATE cashier_shifts
                SET status = 'RECONCILED', reviewed_by = ?, reviewed_at = ?, review_note = ?,
                    updated_at = ?, version = version + 1
                WHERE id = ? AND status = 'SUBMITTED'
                """, reviewerId, timestamp, reviewNote, timestamp, shiftId);
    }

    public int reconcileManagerReview(long shiftId, long reviewerId, long expectedCash, long actualCash,
                                     long variance, String reviewNote, Instant reviewedAt) {
        Timestamp timestamp = Timestamp.from(reviewedAt);
        return jdbc.update("""
                UPDATE cashier_shifts
                SET status = 'RECONCILED', reviewed_by = ?, reviewed_at = ?, review_note = ?,
                    expected_cash = ?, actual_cash = ?, variance = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND status = 'MANAGER_REVIEW'
                """, reviewerId, timestamp, reviewNote, expectedCash, actualCash, variance, timestamp, shiftId);
    }

    public record ReviewData(Long expectedCash, Long actualCash, Long variance) {}

    public Optional<CashierShift> lockOpenShiftByCashier(long cashierId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cashier_shifts WHERE cashier_id = ? AND status = 'OPEN' FOR UPDATE",
                        ShiftRepository::map, cashierId)
                .stream().findFirst();
    }

    /**
     * Payment-path lock. FOR SHARE (not FOR UPDATE) lets concurrent CASH payments
     * validate the same shift, while a concurrent stale-shift flip on this row
     * must wait for the payment transaction to commit.
     */
    public Optional<CashierShift> findOpenShiftForPayment(long cashierId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cashier_shifts WHERE cashier_id = ? AND status = 'OPEN' FOR SHARE",
                        ShiftRepository::map, cashierId)
                .stream().findFirst();
    }

    /**
     * Conditional stale-shift flip (OD-2). Compares the shift against the current
     * business date of its OWN venue, not the venue requested for a new shift.
     */
    public int markStaleShiftForReview(long shiftId, long cashierId,
                                       LocalDate ownVenueCurrentBusinessDate, Instant forcedReviewAt) {
        Timestamp timestamp = Timestamp.from(forcedReviewAt);
        return jdbc.update("""
                UPDATE cashier_shifts
                SET status = 'MANAGER_REVIEW', forced_review_at = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND cashier_id = ? AND status = 'OPEN' AND business_date < ?
                """, timestamp, timestamp, shiftId, cashierId, java.sql.Date.valueOf(ownVenueCurrentBusinessDate));
    }

    /**
     * Atomic insert guarded by the V8 partial unique index
     * uq_cashier_shifts_one_open_per_cashier. Empty result means another OPEN
     * shift already exists for the cashier.
     */
    public Optional<CashierShift> insertOpenShiftIfAbsent(long venueId, long cashierId, LocalDate businessDate,
                                                          long openingFloat, Instant openedAt) {
        Timestamp timestamp = Timestamp.from(openedAt);
        return jdbc.query("""
                        INSERT INTO cashier_shifts (
                            venue_id, cashier_id, business_date, status, opening_float, opened_at,
                            version, created_at, updated_at
                        ) VALUES (?, ?, ?, 'OPEN', ?, ?, 0, ?, ?)
                        ON CONFLICT (cashier_id) WHERE status = 'OPEN' DO NOTHING
                        RETURNING %s
                        """.formatted(COLUMNS), ShiftRepository::map, venueId, cashierId,
                java.sql.Date.valueOf(businessDate), openingFloat, timestamp, timestamp, timestamp)
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
