package com.nightvenue.api.payment;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PaymentSchemaMigrationTests {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.4-alpine"));
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateLatestSchema() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        jdbc = jdbc(postgres.getJdbcUrl());
    }

    @Test void migrate_fromEmptyDatabase_succeeds() {
        String schema = createSchema();
        try {
            Flyway flyway = flyway(schema, postgres.getJdbcUrl()).load();
            flyway.migrate();
            assertEquals("8", flyway.info().current().getVersion().getVersion());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?", Integer.class, schema, "payments"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?", Integer.class, schema, "payment_events"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?", Integer.class, schema, "cashier_shifts"));
        } finally {
            dropSchema(schema);
        }
    }

    @Test void migrate_fromV7_succeeds() {
        String schema = createSchema();
        try {
            Flyway v7 = flyway(schema, postgres.getJdbcUrl())
                    .target(MigrationVersion.fromVersion("7")).load();
            v7.migrate();
            assertEquals("7", v7.info().current().getVersion().getVersion());
            Flyway latest = flyway(schema, postgres.getJdbcUrl()).load();
            latest.migrate();
            assertEquals("8", latest.info().current().getVersion().getVersion());
        } finally {
            dropSchema(schema);
        }
    }

    @Test void payments_rejectNonPositiveAmount() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertCash(f, 0, f.shiftId));
        assertViolation(ex, "23514", "payments_amount_check");
    }

    @Test void payments_rejectInvalidMethod() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertPayment(f, "BITCOIN", "FAILED", 100, null, null, null, null, null, 0, null));
        assertViolation(ex, "23514", "payments_method_check");
    }

    @Test void payments_rejectInvalidStatus() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertCash(f, 100, f.shiftId, "SETTLED", Instant.now()));
        assertViolation(ex, "23514", "payments_status_check");
    }

    @Test void payments_rejectPaidWithoutPaidAt() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertCash(f, 100, f.shiftId, "PAID", null));
        assertViolation(ex, "23514", "ck_payments_paid_at");
    }

    @Test void payments_rejectCashWithoutShift() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertCash(f, 100, null));
        assertViolation(ex, "23514", "ck_payments_shift_method");
    }

    @Test void payments_rejectNonCashWithShift() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> insertPayment(f, "CARD_EDC", "PAID", 100, f.shiftId, null, null, null, null, 0, Instant.now()));
        assertViolation(ex, "23514", "ck_payments_shift_method");
    }

    @Test void payments_providerOrderIdIsUnique() {
        Fixture f = fixture();
        String order = f.suffix + "-provider";
        insertQris(f, order, UUID.randomUUID(), "NOT_SENT", 0);
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> insertQris(f, order, UUID.randomUUID(), "NOT_SENT", 0));
        assertViolation(ex, "23505", "uq_payments_provider_order_id");
    }

    @Test void payments_rejectInvalidChargeState() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> insertPayment(f, "QRIS_MIDTRANS", "PENDING", 100, null,
                        "MIDTRANS", f.suffix + "-qris", UUID.randomUUID(), "INVALID", 0, null));
        assertViolation(ex, "23514", "ck_payments_qris_provider");
    }

    @Test void payments_rejectNegativeChargeAttempts() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> insertPayment(f, "QRIS_MIDTRANS", "PENDING", 100, null,
                        "MIDTRANS", f.suffix + "-qris", UUID.randomUUID(), "NOT_SENT", -1, null));
        assertViolation(ex, "23514", "payments_charge_attempts_check");
    }

    @Test void paymentEvents_rejectNullPaymentId() {
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO payment_events (payment_id,provider,event_key,result,payload_snapshot,received_at)
                VALUES (NULL,'MIDTRANS','null-payment','PROCESSED','{}'::jsonb,?)
                """, Timestamp.from(Instant.now())));
        Throwable root = ex.getMostSpecificCause();
        assertInstanceOf(org.postgresql.util.PSQLException.class, root);
        org.postgresql.util.PSQLException p = (org.postgresql.util.PSQLException) root;
        assertEquals("23502", p.getSQLState());
        assertEquals("payment_id", p.getServerErrorMessage().getColumn());
    }

    @Test void paymentEvents_uniqueProviderEventKey() {
        Fixture f = fixture();
        long paymentId = insertCash(f, 100, f.shiftId);
        insertEvent(paymentId, f.suffix + "-event");
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertEvent(paymentId, f.suffix + "-event"));
        assertViolation(ex, "23505", "uq_payment_events_provider_event");
    }

    @Test void paymentEvents_rejectInvalidResult() {
        Fixture f = fixture();
        long paymentId = insertCash(f, 100, f.shiftId);
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO payment_events (payment_id,provider,event_key,result,payload_snapshot,received_at)
                VALUES (?, 'MIDTRANS', ?, 'DUPLICATE', '{}'::jsonb, ?)
                """, paymentId, f.suffix + "-event", Timestamp.from(Instant.now())));
        assertViolation(ex, "23514", "payment_events_result_check");
    }

    @Test void paymentEvents_restrictPaymentDelete() {
        Fixture f = fixture();
        long paymentId = insertCash(f, 100, f.shiftId);
        insertEvent(paymentId, f.suffix + "-event");
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM payments WHERE id = ?", paymentId));
        assertViolation(ex, "23503", "payment_events_payment_id_fkey");
    }

    @Test void cashierShifts_rejectNegativeOpeningFloat() {
        Fixture f = fixture(false);
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertShift(f, -1, "OPEN", null));
        assertViolation(ex, "23514", "cashier_shifts_opening_float_check");
    }

    @Test void cashierShifts_rejectInvalidStatus() {
        Fixture f = fixture(false);
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertShift(f, 0, "CLOSED", null));
        assertViolation(ex, "23514", "cashier_shifts_status_check");
    }

    @Test void cashierShifts_requireForcedReviewAtForManagerReview() {
        Fixture f = fixture(false);
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertShift(f, 0, "MANAGER_REVIEW", null));
        assertViolation(ex, "23514", "ck_cashier_shifts_forced_review");
    }

    @Test void cashierShifts_oneOpenShiftPerCashier() {
        Fixture f = fixture();
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> insertShift(f, 10, "OPEN", null));
        assertViolation(ex, "23505", "uq_cashier_shifts_one_open_per_cashier");
    }

    @Test void payments_multipleNullProviderOrderId_allowed() {
        Fixture f = fixture();
        insertCash(f, 100, f.shiftId);
        insertCash(f, 200, f.shiftId);
        insertCash(f, 300, f.shiftId);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ? AND provider_order_id IS NULL", Integer.class, f.orderId));
    }

    @Test void payments_restrictOrderDelete() {
        Fixture f = fixture();
        insertCash(f, 100, f.shiftId);
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM orders WHERE id = ?", f.orderId));
        assertViolation(ex, "23503", "payments_order_id_fkey");
    }

    private static FluentConfiguration flyway(String schema, String url) {
        return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").schemas(schema).defaultSchema(schema).createSchemas(true);
    }

    private static String createSchema() {
        String schema = "phase6_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        return schema;
    }

    private static void dropSchema(String schema) {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
    }

    private Fixture fixture() { return fixture(true); }

    private Fixture fixture(boolean withShift) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String venueCode = "S" + suffix;
        String orderNumber = "S-" + suffix;
        Timestamp now = Timestamp.from(Instant.now());
        long venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, venueCode, "Schema " + suffix, now, now);
        long userId = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', 'Schema Test', 'CASHIER', true, 0, ?, ?) RETURNING id
                """, Long.class, suffix + "@test.local", now, now);
        long orderId = jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', 100, 100, ?, ?, ?, 0) RETURNING id
                """, Long.class, venueId, orderNumber, userId, now, now, now);
        Fixture f = new Fixture(suffix, venueId, userId, orderId, null);
        if (withShift) {
            long shiftId = insertShift(f, 0, "OPEN", null);
            f = new Fixture(suffix, venueId, userId, orderId, shiftId);
        }
        return f;
    }

    private long insertShift(Fixture f, long openingFloat, String status, Instant forcedAt) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts (venue_id,cashier_id,business_date,status,opening_float,opened_at,
                                            forced_review_at,version,created_at,updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?) RETURNING id
                """, Long.class, f.venueId, f.cashierId, java.sql.Date.valueOf(LocalDate.now()), status, openingFloat,
                now, ts(forcedAt), now, now);
    }

    private static Timestamp ts(Instant v) { return v == null ? null : Timestamp.from(v); }

    private long insertCash(Fixture f, long amount, Long shiftId) {
        return insertCash(f, amount, shiftId, "PAID", Instant.now());
    }

    private long insertCash(Fixture f, long amount, Long shiftId, String status, Instant paidAt) {
        return insertPayment(f, "CASH", status, amount, shiftId, null, null, null, null, 0, paidAt);
    }

    private long insertQris(Fixture f, String providerOrderId, UUID providerKey, String chargeState, int attempts) {
        return insertPayment(f, "QRIS_MIDTRANS", "PENDING", 100, null, "MIDTRANS", providerOrderId,
                providerKey, chargeState, attempts, null);
    }

    private long insertPayment(Fixture f, String method, String status, long amount, Long shiftId,
                               String provider, String providerOrderId, UUID providerKey, String chargeState,
                               int attempts, Instant paidAt) {
        return jdbc.queryForObject("""
                INSERT INTO payments (order_id,venue_id,business_date,shift_id,method,amount,status,provider,
                    provider_order_id,provider_idempotency_key,charge_state,charge_attempts,paid_at,created_by,
                    created_at,updated_at,version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, f.orderId, f.venueId, java.sql.Date.valueOf(LocalDate.now()), shiftId, method, amount, status,
                provider, providerOrderId, providerKey, chargeState, attempts, ts(paidAt), f.cashierId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private void insertEvent(long paymentId, String eventKey) {
        jdbc.update("""
                INSERT INTO payment_events (payment_id,provider,event_key,result,payload_snapshot,received_at)
                VALUES (?, 'MIDTRANS', ?, 'PROCESSED', '{}'::jsonb, ?)
                """, paymentId, eventKey, Timestamp.from(Instant.now()));
    }

    private static void assertViolation(DataIntegrityViolationException ex, String sqlState, String constraint) {
        Throwable root = ex.getMostSpecificCause();
        assertInstanceOf(org.postgresql.util.PSQLException.class, root);
        org.postgresql.util.PSQLException p = (org.postgresql.util.PSQLException) root;
        assertEquals(sqlState, p.getSQLState());
        assertEquals(constraint, p.getServerErrorMessage().getConstraint());
    }

    private record Fixture(String suffix, long venueId, long cashierId, long orderId, Long shiftId) {}
}
