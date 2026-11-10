package com.nightvenue.api.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentRepository {
    private static final String COLUMNS = """
            id, order_id, venue_id, business_date, shift_id, method, amount, status, provider,
            provider_order_id, provider_transaction_id, provider_idempotency_key, charge_state,
            charge_started_at, charge_attempts, expires_at, reference_note, paid_at, created_by,
            created_at, updated_at, version
            """;

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lock-free read for the payment GET endpoint; never use it where a row lock is required. */
    public Optional<Payment> findById(long paymentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payments WHERE id = ?",
                        PaymentRepository::map, paymentId)
                .stream().findFirst();
    }

    public Optional<Payment> findForUpdate(long paymentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payments WHERE id = ? FOR UPDATE",
                        PaymentRepository::map, paymentId)
                .stream().findFirst();
    }

    public boolean existsActiveByOrder(long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payments
                    WHERE order_id = ? AND status IN ('PENDING', 'PAID')
                )
                """, Boolean.class, orderId));
    }

    public long sumByStatus(long orderId, PaymentStatus status) {
        Long amount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM payments
                WHERE order_id = ? AND status = ?
                """, Long.class, orderId, status.name());
        return amount == null ? 0 : amount;
    }

    public long sumActiveByOrder(long orderId) {
        Long amount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM payments
                WHERE order_id = ? AND status IN ('PENDING', 'PAID')
                """, Long.class, orderId);
        return amount == null ? 0 : amount;
    }

    public List<Payment> findByOrder(long orderId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payments WHERE order_id = ? ORDER BY id", PaymentRepository::map, orderId);
    }

    public Optional<Payment> findByProviderOrderId(String providerOrderId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payments WHERE provider_order_id = ?",
                        PaymentRepository::map, providerOrderId)
                .stream().findFirst();
    }

    public Payment insert(
            long orderId,
            long venueId,
            LocalDate businessDate,
            Long shiftId,
            PaymentMethod method,
            long amount,
            PaymentStatus status,
            String provider,
            String providerOrderId,
            String providerTransactionId,
            UUID providerIdempotencyKey,
            ChargeState chargeState,
            Instant chargeStartedAt,
            int chargeAttempts,
            Instant expiresAt,
            String referenceNote,
            Instant paidAt,
            long createdBy,
            Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        String sql = """
                INSERT INTO payments (
                    order_id, venue_id, business_date, shift_id, method, amount, status, provider,
                    provider_order_id, provider_transaction_id, provider_idempotency_key, charge_state,
                    charge_started_at, charge_attempts, expires_at, reference_note, paid_at, created_by,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                RETURNING %s
                """.formatted(COLUMNS);
        return jdbc.queryForObject(sql, PaymentRepository::map,
                orderId, venueId, java.sql.Date.valueOf(businessDate), shiftId, method.name(), amount,
                status.name(), provider, providerOrderId, providerTransactionId, providerIdempotencyKey,
                chargeState == null ? null : chargeState.name(), timestampOrNull(chargeStartedAt), chargeAttempts,
                timestampOrNull(expiresAt), referenceNote, timestampOrNull(paidAt), createdBy, timestamp, timestamp);
    }

    private static Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Payment map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Payment(
                rs.getLong("id"), rs.getLong("order_id"), rs.getLong("venue_id"),
                rs.getObject("business_date", LocalDate.class), rs.getObject("shift_id", Long.class),
                PaymentMethod.valueOf(rs.getString("method")), rs.getLong("amount"),
                PaymentStatus.valueOf(rs.getString("status")), rs.getString("provider"),
                rs.getString("provider_order_id"), rs.getString("provider_transaction_id"),
                rs.getObject("provider_idempotency_key", UUID.class),
                enumValue(ChargeState.class, rs.getString("charge_state")),
                instantOrNull(rs.getTimestamp("charge_started_at")), rs.getInt("charge_attempts"),
                instantOrNull(rs.getTimestamp("expires_at")), rs.getString("reference_note"),
                instantOrNull(rs.getTimestamp("paid_at")), rs.getLong("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }

    private static Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
