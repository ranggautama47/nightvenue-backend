package com.nightvenue.api.shift;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ShiftReconciliationMigrationTests {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.4-alpine"));

    static JdbcTemplate jdbc;

    @BeforeAll
    static void init() {
        jdbc = jdbc(postgres.getJdbcUrl());
    }

    @Test
    void migrate_emptyDatabase_toV11() {
        String schema = createSchema();
        try {
            Flyway flyway = flyway(schema).load();
            flyway.migrate();

            assertEquals("11", flyway.info().current().getVersion().getVersion());
            assertEquals(8, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'cashier_shifts'
                      AND column_name IN ('submitted_at', 'expected_cash', 'actual_cash', 'variance',
                                          'submit_note', 'reviewed_by', 'reviewed_at', 'review_note')
                    """, Integer.class, schema));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_indexes WHERE schemaname = ?
                      AND indexname = 'ix_payments_shift_id'
                    """, Integer.class, schema));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_indexes WHERE schemaname = ?
                      AND indexname = 'ix_cashier_shifts_open_venue_business_date'
                    """, Integer.class, schema));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void migrate_v10OpenAndManagerReview_preservesExistingRows() {
        String schema = createSchema();
        try {
            flyway(schema).target(MigrationVersion.fromVersion("10")).load().migrate();
            Fixture fixture = fixture(schema);
            long openShift = insertLegacyShift(schema, fixture.venueId, fixture.cashierId, "OPEN", null);
            Instant forcedAt = Instant.parse("2026-11-01T00:00:00Z");
            long reviewShift = insertLegacyShift(schema, fixture.venueId, fixture.otherCashierId,
                    "MANAGER_REVIEW", forcedAt);

            flyway(schema).load().migrate();

            assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM " + schema + ".cashier_shifts WHERE id=?", String.class, openShift));
            assertNull(jdbc.queryForObject("SELECT actual_cash FROM " + schema + ".cashier_shifts WHERE id=?", Long.class, openShift));
            assertEquals("MANAGER_REVIEW", jdbc.queryForObject("SELECT status FROM " + schema + ".cashier_shifts WHERE id=?", String.class, reviewShift));
            assertEquals(Timestamp.from(forcedAt), jdbc.queryForObject(
                    "SELECT forced_review_at FROM " + schema + ".cashier_shifts WHERE id=?", Timestamp.class, reviewShift));
            assertNull(jdbc.queryForObject("SELECT expected_cash FROM " + schema + ".cashier_shifts WHERE id=?", Long.class, reviewShift));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void reconciledNormalAndCutoffRows_areBothValid() {
        String schema = createSchema();
        try {
            flyway(schema).load().migrate();
            Fixture fixture = fixture(schema);
            Instant now = Instant.parse("2026-11-01T12:00:00Z");
            long normal = insertReconciledShift(schema, fixture.venueId, fixture.cashierId,
                    fixture.reviewerId, now, now, null, 100L, 125L, 25L, "cashier note");
            long cutoff = insertReconciledShift(schema, fixture.venueId, fixture.otherCashierId,
                    fixture.reviewerId, now, null, now.minusSeconds(3600), 100L, 90L, -10L, null);

            assertEquals(now, jdbc.queryForObject("SELECT submitted_at FROM " + schema + ".cashier_shifts WHERE id=?",
                    Timestamp.class, normal).toInstant());
            assertNull(jdbc.queryForObject("SELECT submitted_at FROM " + schema + ".cashier_shifts WHERE id=?",
                    Timestamp.class, cutoff));
            assertNotNull(jdbc.queryForObject("SELECT forced_review_at FROM " + schema + ".cashier_shifts WHERE id=?",
                    Timestamp.class, cutoff));
            assertEquals("RECONCILED", jdbc.queryForObject("SELECT status FROM " + schema + ".cashier_shifts WHERE id=?",
                    String.class, normal));
            assertEquals("RECONCILED", jdbc.queryForObject("SELECT status FROM " + schema + ".cashier_shifts WHERE id=?",
                    String.class, cutoff));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void newCheckConstraints_rejectInvalidRows() {
        String schema = createSchema();
        try {
            flyway(schema).load().migrate();
            Fixture f = fixture(schema);
            Instant now = Instant.now();

            assertCheck("ck_cashier_shifts_submission", () -> insertReconciledLike(
                    schema, f, "SUBMITTED", null, null, null, null, null, null, null));
            assertCheck("ck_cashier_shifts_reconciliation_values", () -> insertReconciledLike(
                    schema, f, "OPEN", null, null, 1L, null, null, null, null));
            assertCheck("ck_cashier_shifts_reconciliation_values", () -> insertReconciledLike(
                    schema, f, "OPEN", null, null, 100L, 120L, 19L, null, null));
            assertCheck("ck_cashier_shifts_expected_cash_nonnegative", () -> insertReconciledLike(
                    schema, f, "OPEN", null, null, -1L, 0L, 1L, null, null));
            assertCheck("ck_cashier_shifts_actual_cash_nonnegative", () -> insertReconciledLike(
                    schema, f, "OPEN", null, null, 0L, -1L, -1L, null, null));
            assertCheck("ck_cashier_shifts_reviewed_by_not_owner", () -> insertReconciledLike(
                    schema, f, "RECONCILED", now, now, 1L, 1L, 0L, f.cashierId, null));
            assertCheck("ck_cashier_shifts_reconciled", () -> insertReconciledLike(
                    schema, f, "RECONCILED", now, now, 1L, 1L, 0L, null, null));

            long openShift = insertLegacyShift(schema, f.venueId, f.cashierId, "OPEN", null);
            for (String column : new String[]{"reviewed_by", "reviewed_at", "review_note"}) {
                assertCheck("ck_cashier_shifts_review_fields_only_when_reconciled", () -> {
                    if (column.equals("reviewed_by")) {
                        jdbc.update("UPDATE " + schema + ".cashier_shifts SET reviewed_by=? WHERE id=?",
                                f.reviewerId, openShift);
                    } else if (column.equals("reviewed_at")) {
                        jdbc.update("UPDATE " + schema + ".cashier_shifts SET reviewed_at=? WHERE id=?",
                                Timestamp.from(now), openShift);
                    } else {
                        jdbc.update("UPDATE " + schema + ".cashier_shifts SET review_note='note' WHERE id=?", openShift);
                    }
                });
            }

            // Review-note presence is a service rule, not a database CHECK.
            long noNote = insertReconciledShift(schema, f.venueId, f.otherCashierId,
                    f.reviewerId, now, null, now, 1L, 1L, 0L, null);
            assertNull(jdbc.queryForObject("SELECT review_note FROM " + schema + ".cashier_shifts WHERE id=?",
                    String.class, noNote));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void preReviewStates_requireCleanReconciliationFields() {
        String schema = createSchema();
        try {
            flyway(schema).load().migrate();
            Fixture f = fixture(schema);
            Instant now = Instant.now();

            long openShift = insertLegacyShift(schema, f.venueId, f.cashierId, "OPEN", null);
            assertCheck("ck_cashier_shifts_pre_review_clean", () -> jdbc.update("""
                    UPDATE %s.cashier_shifts
                    SET expected_cash=100, actual_cash=100, variance=0
                    WHERE id=?
                    """.formatted(schema), openShift));

            long managerReviewShift = insertLegacyShift(schema, f.venueId, f.otherCashierId,
                    "MANAGER_REVIEW", now);
            assertCheck("ck_cashier_shifts_pre_review_clean", () -> jdbc.update(
                    "UPDATE " + schema + ".cashier_shifts SET submitted_at=? WHERE id=?",
                    Timestamp.from(now), managerReviewShift));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM %s.cashier_shifts
                    WHERE id=? AND submitted_at IS NULL AND expected_cash IS NULL AND actual_cash IS NULL
                      AND variance IS NULL AND submit_note IS NULL AND reviewed_by IS NULL
                      AND reviewed_at IS NULL AND review_note IS NULL
                    """.formatted(schema), Integer.class, managerReviewShift));

            long submittedWithoutReviewNote = insertReconciledLike(schema, f, "SUBMITTED",
                    now, null, 100L, 125L, 25L, null, null);
            assertNull(jdbc.queryForObject("SELECT review_note FROM " + schema + ".cashier_shifts WHERE id=?",
                    String.class, submittedWithoutReviewNote));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void reviewedByForeignKey_rejectsMissingUserAndRestrictsDeletion() {
        String schema = createSchema();
        try {
            flyway(schema).load().migrate();
            Fixture f = fixture(schema);
            Instant now = Instant.now();
            assertSqlState("23503", "cashier_shifts_reviewed_by_fkey", () -> insertReconciledLike(
                    schema, f, "RECONCILED", now, now, 1L, 1L, 0L, 999999999L, null));

            long reconciled = insertReconciledShift(schema, f.venueId, f.cashierId,
                    f.reviewerId, now, now, null, 1L, 1L, 0L, null);
            assertTrue(reconciled > 0);
            assertSqlState("23503", "cashier_shifts_reviewed_by_fkey",
                    () -> jdbc.update("DELETE FROM " + schema + ".users WHERE id=?", f.reviewerId));
        } finally {
            dropSchema(schema);
        }
    }

    private static void assertCheck(String constraint, Runnable operation) {
        assertSqlState("23514", constraint, operation);
    }

    private static void assertSqlState(String sqlState, String constraint, Runnable operation) {
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation::run);
        Throwable root = exception.getMostSpecificCause();
        assertInstanceOf(PSQLException.class, root);
        PSQLException postgresException = (PSQLException) root;
        assertEquals(sqlState, postgresException.getSQLState());
        assertEquals(constraint, postgresException.getServerErrorMessage().getConstraint());
    }

    private static long insertReconciledLike(String schema, Fixture f, String status,
                                             Instant submittedAt, Instant reviewedAt,
                                             Long expected, Long actual, Long variance,
                                             Long reviewedBy, String reviewNote) {
        return jdbc.queryForObject(("""
                INSERT INTO %s.cashier_shifts (venue_id,cashier_id,business_date,status,opening_float,opened_at,
                    submitted_at,expected_cash,actual_cash,variance,reviewed_by,reviewed_at,review_note,
                    version,created_at,updated_at)
                VALUES (?, ?, CURRENT_DATE, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?) RETURNING id
                """).formatted(schema), Long.class, f.venueId, f.cashierId, status, Timestamp.from(Instant.now()),
                ts(submittedAt), expected, actual, variance, reviewedBy, ts(reviewedAt), reviewNote,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private static long insertReconciledShift(String schema, long venueId, long cashierId, long reviewerId,
                                              Instant reviewedAt, Instant submittedAt, Instant forcedAt,
                                              long expected, long actual, long variance, String reviewNote) {
        return jdbc.queryForObject(("""
                INSERT INTO %s.cashier_shifts (venue_id,cashier_id,business_date,status,opening_float,opened_at,
                    forced_review_at,submitted_at,expected_cash,actual_cash,variance,reviewed_by,reviewed_at,
                    review_note,version,created_at,updated_at)
                VALUES (?, ?, CURRENT_DATE, 'RECONCILED', 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?) RETURNING id
                """).formatted(schema), Long.class, venueId, cashierId, Timestamp.from(Instant.now()), ts(forcedAt),
                ts(submittedAt), expected, actual, variance, reviewerId, ts(reviewedAt), reviewNote,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private static long insertLegacyShift(String schema, long venueId, long cashierId, String status, Instant forcedAt) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject(("""
                INSERT INTO %s.cashier_shifts (venue_id,cashier_id,business_date,status,opening_float,opened_at,
                    forced_review_at,version,created_at,updated_at)
                VALUES (?, ?, CURRENT_DATE, ?, 0, ?, ?, 0, ?, ?) RETURNING id
                """).formatted(schema), Long.class, venueId, cashierId, status, now, ts(forcedAt), now, now);
    }

    private static Fixture fixture(String schema) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        long venueId = jdbc.queryForObject(("""
                INSERT INTO %s.venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """).formatted(schema), Long.class, "V" + suffix, "Venue " + suffix, now, now);
        long cashierId = insertUser(schema, suffix + "-c@test.local", "CASHIER", now);
        long otherCashierId = insertUser(schema, suffix + "-c2@test.local", "CASHIER", now);
        long reviewerId = insertUser(schema, suffix + "-m@test.local", "MANAGER", now);
        return new Fixture(venueId, cashierId, otherCashierId, reviewerId);
    }

    private static long insertUser(String schema, String email, String role, Timestamp now) {
        return jdbc.queryForObject(("""
                INSERT INTO %s.users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', 'Migration test', ?, true, 0, ?, ?) RETURNING id
                """).formatted(schema), Long.class, email, role, now, now);
    }

    private static FluentConfiguration flyway(String schema) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").schemas(schema).defaultSchema(schema).createSchemas(true);
    }

    private static String createSchema() {
        String schema = "shift_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        return schema;
    }

    private static void dropSchema(String schema) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private record Fixture(long venueId, long cashierId, long otherCashierId, long reviewerId) {}
}
