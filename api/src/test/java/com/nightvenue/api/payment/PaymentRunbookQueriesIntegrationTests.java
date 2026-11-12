package com.nightvenue.api.payment;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.shift.ShiftRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-C runbook validation. Executes every diagnostic SELECT documented in
 * ADR-023 (docs/DECISIONS.md) against real PostgreSQL (Testcontainers) and
 * proves each documented predicate selects exactly the intended rows.
 * Read-only production semantics: the test only INSERTs/DELETEs its own
 * disposable fixtures, never mutates payment state, and never calls Xendit.
 * Deliberately not transactional: each test seeds and cleans its own rows.
 */
@SpringBootTest(properties = {
        "nightvenue.seed.enabled=false",
        "nightvenue.payment.xendit.secret-key=test-secret-not-real",
        "nightvenue.payment.reconciliation.enabled=false",
        "nightvenue.payment.reconciliation.min-age=PT1H",
        "nightvenue.payment.reconciliation.batch-size=50",
        "nightvenue.payment.reconciliation.max-per-cycle=50",
        "nightvenue.payment.reconciliation.fixed-delay=PT1H"
})
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class PaymentRunbookQueriesIntegrationTests {

    // =====================================================================
    // Diagnostic SELECTs — byte-equivalent (whitespace-normalized) to the
    // ADR-023 ```sql blocks in docs/DECISIONS.md. Do not edit one side only.
    // =====================================================================

    private static final String Q1 = """
            -- Q1: Xendit QRIS rows stuck in SENDING / NOT_SENT (threshold = now - min_age)
            SELECT id, charge_state, charge_started_at, provider_reference_id
            FROM payments
            WHERE provider = 'XENDIT'
              AND method = 'QRIS'
              AND status = 'PENDING'
              AND charge_state IN ('SENDING', 'NOT_SENT')
              AND (charge_started_at IS NULL OR charge_started_at < ?)
            ORDER BY charge_started_at ASC NULLS LAST, id ASC;
            """;

    private static final String Q2 = """
            -- Q2: Xendit QRIS AMBIGUOUS rows without provider_request_id (threshold = now - min_age)
            SELECT id, charge_state, charge_started_at, provider_reference_id
            FROM payments
            WHERE provider = 'XENDIT'
              AND method = 'QRIS'
              AND status = 'PENDING'
              AND charge_state = 'AMBIGUOUS'
              AND provider_request_id IS NULL
              AND (charge_started_at IS NULL OR charge_started_at < ?)
            ORDER BY charge_started_at ASC NULLS LAST, id ASC;
            """;

    private static final String Q3 = """
            -- Q3: reconciliation candidates (exact mirror of PaymentRepository.findXenditReconciliationCandidates)
            SELECT id
            FROM payments
            WHERE provider = 'XENDIT'
              AND method = 'QRIS'
              AND status = 'PENDING'
              AND charge_state IN ('ACCEPTED', 'AMBIGUOUS')
              AND provider_request_id IS NOT NULL
              AND charge_started_at IS NOT NULL
              AND charge_started_at < ?
            ORDER BY charge_started_at ASC, id ASC
            LIMIT ?;
            """;

    private static final String Q4 = """
            -- Q4: order totals, active payment reservations (BR-28) and unresolved Xendit rows (BR-20)
            SELECT o.id AS order_id,
                   o.venue_id,
                   o.status AS order_status,
                   o.total_due,
                   o.paid_total,
                   COALESCE(a.active_total, 0) AS active_reservation_total,
                   o.total_due - COALESCE(a.active_total, 0) AS outstanding_amount,
                   COALESCE(x.unresolved_xendit, '') AS unresolved_xendit_payments
            FROM orders o
            LEFT JOIN LATERAL (
                SELECT COALESCE(SUM(amount), 0) AS active_total
                FROM payments
                WHERE payments.order_id = o.id
                  AND payments.status IN ('PENDING', 'PAID')
            ) a ON TRUE
            LEFT JOIN LATERAL (
                SELECT string_agg(p.id::text || ':' || p.charge_state, ', ' ORDER BY p.id) AS unresolved_xendit
                FROM payments p
                WHERE p.order_id = o.id
                  AND p.provider = 'XENDIT'
                  AND p.method = 'QRIS'
                  AND p.status = 'PENDING'
            ) x ON TRUE
            WHERE o.venue_id = ?
            ORDER BY o.id ASC;
            """;

    private static final String Q5 = """
            -- Q5: in-flight idempotency keys (response_status IS NULL, threshold = now - min_age)
            SELECT key, scope, user_id, request_hash, created_at
            FROM idempotency_keys
            WHERE response_status IS NULL
              AND created_at < ?
            ORDER BY created_at ASC;
            """;

    private static final String Q6 = """
            -- Q6: queryable PENDING Xendit candidates for read-only provider Status API verification
            SELECT id, provider_request_id, charge_started_at, charge_state
            FROM payments
            WHERE provider = 'XENDIT'
              AND method = 'QRIS'
              AND status = 'PENDING'
              AND charge_state IN ('ACCEPTED', 'AMBIGUOUS')
              AND provider_request_id IS NOT NULL
              AND charge_started_at IS NOT NULL
              AND charge_started_at < ?
            ORDER BY charge_started_at ASC, id ASC
            LIMIT ?;
            """;

    @Autowired JdbcTemplate jdbc;
    @Autowired ShiftRepository shiftRepository;

    private String fixture;
    private long venueId;
    private long cashierId;
    private long shiftId;
    private int orderCounter;
    private Instant base;
    private Instant oldTime;
    private Instant recentTime;
    private Timestamp oldTs;
    private Timestamp recentTs;
    private Timestamp cutoffTs;
    private final List<UUID> keys = new ArrayList<>();

    // fixture payment ids (filled in seedFixtures)
    private long p1, p2, p3, p4, p5, p6, p7, p8, p9, p10;
    private String requestP4;
    private String requestP5;

    @BeforeEach
    void setup() {
        fixture = "M5C-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        cashierId = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', ?, 'CASHIER', true, 0, ?, ?) RETURNING id
                """, Long.class, fixture.toLowerCase() + "-c@test.local", fixture + "-c", now, now);
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,tax_rate_bps,
                                    service_charge_bps,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, 0, 0, true, ?, ?) RETURNING id
                """, Long.class, fixture, fixture, now, now);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueId);
        shiftId = shiftRepository.insertOpenShift(venueId, cashierId, LocalDate.now(), 0L, Instant.now()).id();
        seedFixtures();
    }

    @AfterEach
    void cleanup() {
        if (fixture == null) return;
        keys.forEach(key -> jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key));
        jdbc.update("DELETE FROM payment_events WHERE payment_id IN (SELECT id FROM payments WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM payments WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM orders WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM cashier_shifts WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM user_venues WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM users WHERE id = ?", cashierId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    /**
     * Ten V9-valid fixtures on ten distinct orders with distinct amounts so
     * every Q4 cell is asserted exactly:
     * p1 old SENDING (req NULL), p2 old NOT_SENT (req NULL, started NULL),
     * p3 old AMBIGUOUS (req NULL), p4 old ACCEPTED (req NOT NULL),
     * p5 old AMBIGUOUS (req NOT NULL), p6 PAID CASH, p7 FAILED QRIS (REJECTED),
     * p8 recent SENDING, p9 unrelated PAID CASH, p10 recent AMBIGUOUS (req NULL).
     */
    private void seedFixtures() {
        base = Instant.now();
        oldTime = base.minus(Duration.ofHours(2));
        recentTime = base;
        oldTs = Timestamp.from(oldTime);
        recentTs = Timestamp.from(recentTime);
        // diagnostic threshold: one hour before the seed instant
        cutoffTs = Timestamp.from(base.minus(Duration.ofHours(1)));

        p1 = seedQris(createOrder(1_000_000), 300_000, "SENDING", "PENDING", null, oldTs);
        p2 = seedQris(createOrder(900_000), 200_000, "NOT_SENT", "PENDING", null, null);
        p3 = seedQris(createOrder(700_000), 150_000, "AMBIGUOUS", "PENDING", null, oldTs);
        requestP4 = "pr-" + UUID.randomUUID();
        p4 = seedQris(createOrder(800_000), 500_000, "ACCEPTED", "PENDING", requestP4, oldTs);
        requestP5 = "pr-" + UUID.randomUUID();
        p5 = seedQris(createOrder(600_000), 250_000, "AMBIGUOUS", "PENDING", requestP5, oldTs);
        p6 = seedCash(createOrder(400_000), 100_000);
        p7 = seedQris(createOrder(850_000), 450_000, "REJECTED", "FAILED", null, oldTs);
        p8 = seedQris(createOrder(300_000), 50_000, "SENDING", "PENDING", null, recentTs);
        p9 = seedCash(createOrder(500_000), 75_000);
        p10 = seedQris(createOrder(400_000), 60_000, "AMBIGUOUS", "PENDING", null, recentTs);
    }

    // ---------- fixture helpers ----------

    private long createOrder(long totalDue) {
        orderCounter++;
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', ?, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, venueId, fixture + "-O" + orderCounter, cashierId, totalDue, totalDue, now, now, now);
    }

    /** V9 branch-3 Xendit QRIS row: reference seeded then updated to the real nv_pay_<id> value. */
    private long seedQris(long orderId, long amount, String chargeState, String status,
                          String requestId, Timestamp chargeStartedAt) {
        Timestamp now = Timestamp.from(Instant.now());
        long paymentId = jdbc.queryForObject("""
                INSERT INTO payments (order_id,venue_id,business_date,method,amount,status,provider,
                                      provider_reference_id,provider_request_id,charge_state,
                                      charge_started_at,charge_attempts,created_by,created_at,updated_at,version)
                VALUES (?, ?, CURRENT_DATE, 'QRIS', ?, ?, 'XENDIT',
                        'seed_m5c_' || ?::text, ?, ?, ?, 1, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, orderId, venueId, amount, status, UUID.randomUUID().toString(),
                requestId, chargeState, chargeStartedAt, cashierId, now, now);
        jdbc.update("UPDATE payments SET provider_reference_id = ? WHERE id = ?", "nv_pay_" + paymentId, paymentId);
        return paymentId;
    }

    /** V9 branch-1 offline CASH row: PAID with shift and paid_at, every provider column NULL. */
    private long seedCash(long orderId, long amount) {
        Timestamp now = Timestamp.from(Instant.now());
        long paymentId = jdbc.queryForObject("""
                INSERT INTO payments (order_id,venue_id,business_date,method,amount,status,shift_id,
                                      paid_at,created_by,created_at,updated_at,version)
                VALUES (?, ?, CURRENT_DATE, 'CASH', ?, 'PAID', ?, ?, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, orderId, venueId, amount, shiftId, now, cashierId, now, now);
        // keep the fixture order consistent with what the production settlement path writes
        jdbc.update("UPDATE orders SET paid_total = ? WHERE id = ?", amount, orderId);
        return paymentId;
    }

    private UUID seedIdempotencyKey(Instant createdAt, Integer responseStatus) {
        UUID key = UUID.randomUUID();
        keys.add(key);
        jdbc.update("""
                INSERT INTO idempotency_keys (key, scope, user_id, request_hash, response_status, created_at)
                VALUES (?, 'PAYMENT_CREATE', ?, ?, ?, ?)
                """, key, cashierId, "h".repeat(64), responseStatus, Timestamp.from(createdAt));
        return key;
    }

    // ---------- execution helpers ----------

    /** Strips the doc-marker comment lines and the trailing semicolon for JDBC execution. */
    private static String executable(String sql) {
        StringBuilder stripped = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            if (line.trim().startsWith("--")) continue;
            stripped.append(line).append('\n');
        }
        String out = stripped.toString().trim();
        if (out.endsWith(";")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private List<Long> runIds(String sql, Object... args) {
        return jdbc.query(executable(sql), (rs, i) -> rs.getLong(1), args);
    }

    private record Q4Row(long orderId, long venueId, String orderStatus, long totalDue, long paidTotal,
                         long activeTotal, long outstanding, String unresolved) {}

    private Map<Long, Q4Row> runQ4() {
        Map<Long, Q4Row> rows = new LinkedHashMap<>();
        jdbc.query(executable(Q4), rs -> {
            rows.put(rs.getLong("order_id"), new Q4Row(
                    rs.getLong("order_id"), rs.getLong("venue_id"), rs.getString("order_status"),
                    rs.getLong("total_due"), rs.getLong("paid_total"),
                    rs.getLong("active_reservation_total"), rs.getLong("outstanding_amount"),
                    rs.getString("unresolved_xendit_payments")));
        }, venueId);
        return rows;
    }

    // =====================================================================
    // Q1 — stuck SENDING / NOT_SENT
    // =====================================================================

    @Test
    void q1_stuckSendingAndNotSent_returnsOnlyOldRows() {
        // old SENDING first (charge_started_at 2h ago), old NOT_SENT second (NULL timestamp, NULLS LAST)
        assertEquals(List.of(p1, p2), runIds(Q1, cutoffTs),
                "Q1 must return exactly the old SENDING and old NOT_SENT fixtures");
        // negatives: recent SENDING, AMBIGUOUS rows, FAILED, PAID CASH are excluded
        assertTrue(!runIds(Q1, cutoffTs).contains(p8), "recent SENDING below threshold is excluded");
        assertTrue(!runIds(Q1, cutoffTs).contains(p3) && !runIds(Q1, cutoffTs).contains(p10),
                "AMBIGUOUS rows are not SENDING/NOT_SENT");
        assertTrue(!runIds(Q1, cutoffTs).contains(p7), "FAILED row is excluded");
        assertTrue(!runIds(Q1, cutoffTs).contains(p6) && !runIds(Q1, cutoffTs).contains(p9),
                "unrelated CASH rows are excluded");
    }

    // =====================================================================
    // Q2 — AMBIGUOUS without provider_request_id
    // =====================================================================

    @Test
    void q2_ambiguousWithoutRequestId_returnsOnlyOldUnqueryableRow() {
        assertEquals(List.of(p3), runIds(Q2, cutoffTs),
                "Q2 must return exactly the old AMBIGUOUS row without a provider request id");
        assertTrue(!runIds(Q2, cutoffTs).contains(p10), "recent AMBIGUOUS below threshold is excluded");
        assertTrue(!runIds(Q2, cutoffTs).contains(p5), "AMBIGUOUS with a request id is queryable and excluded");
        assertTrue(!runIds(Q2, cutoffTs).contains(p7), "FAILED row is excluded");
        assertTrue(!runIds(Q2, cutoffTs).contains(p1) && !runIds(Q2, cutoffTs).contains(p2),
                "SENDING/NOT_SENT rows are not AMBIGUOUS");
    }

    // =====================================================================
    // Q3 — reconciliation candidates (production semantics)
    // =====================================================================

    @Test
    void q3_reconciliationCandidates_mirrorProductionSemantics() {
        assertEquals(List.of(p4, p5), runIds(Q3, cutoffTs, 10),
                "Q3 must return exactly the old ACCEPTED and old AMBIGUOUS rows that carry a request id");
        // the bound LIMIT is honored: oldest candidate first
        assertEquals(List.of(p4), runIds(Q3, cutoffTs, 1), "LIMIT ? binding must be honored");
        // negatives
        assertTrue(!runIds(Q3, cutoffTs, 10).contains(p1) && !runIds(Q3, cutoffTs, 10).contains(p2),
                "SENDING/NOT_SENT are not reconciliation candidates");
        assertTrue(!runIds(Q3, cutoffTs, 10).contains(p3) && !runIds(Q3, cutoffTs, 10).contains(p10),
                "AMBIGUOUS without request id is not a reconciliation candidate");
        assertTrue(!runIds(Q3, cutoffTs, 10).contains(p8), "recent SENDING is excluded");
        assertTrue(!runIds(Q3, cutoffTs, 10).contains(p7), "FAILED row is excluded");
        assertTrue(!runIds(Q3, cutoffTs, 10).contains(p6) && !runIds(Q3, cutoffTs, 10).contains(p9),
                "CASH rows are excluded");
    }

    // =====================================================================
    // Q4 — orders with active payment reservations (BR-28 / BR-20)
    // =====================================================================

    @Test
    void q4_orderActiveReservations_matchBr28AndBr20Evidence() {
        Map<Long, Q4Row> rows = runQ4();
        assertEquals(10, rows.size(), "all seeded orders of the venue are listed exactly once");

        // every order row carries the correct venue and OPEN status
        rows.values().forEach(r -> {
            assertEquals(venueId, r.venueId());
            assertEquals("OPEN", r.orderStatus());
        });

        // unresolved string is "<payment_id>:<charge_state>" per Xendit PENDING row
        rows.values().stream()
                .filter(r -> r.unresolved().contains(String.valueOf(p1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("order holding p1 must be listed"));
        assertQ4Row(rows, p1, 1_000_000, 0, 300_000, 700_000, p1 + ":SENDING");
        assertQ4Row(rows, p2, 900_000, 0, 200_000, 700_000, p2 + ":NOT_SENT");
        assertQ4Row(rows, p3, 700_000, 0, 150_000, 550_000, p3 + ":AMBIGUOUS");
        assertQ4Row(rows, p4, 800_000, 0, 500_000, 300_000, p4 + ":ACCEPTED");
        assertQ4Row(rows, p5, 600_000, 0, 250_000, 350_000, p5 + ":AMBIGUOUS");
        // PAID CASH counts as active for the exact sumActiveByOrder predicate, but is not unresolved-Xendit
        assertQ4Row(rows, p6, 400_000, 100_000, 100_000, 300_000, "");
        // FAILED releases the reservation entirely
        assertQ4Row(rows, p7, 850_000, 0, 0, 850_000, "");
        // recent rows still reserve and still show as unresolved
        assertQ4Row(rows, p8, 300_000, 0, 50_000, 250_000, p8 + ":SENDING");
        assertQ4Row(rows, p9, 500_000, 75_000, 75_000, 425_000, "");
        assertQ4Row(rows, p10, 400_000, 0, 60_000, 340_000, p10 + ":AMBIGUOUS");
    }

    private void assertQ4Row(Map<Long, Q4Row> rows, long paymentId, long totalDue, long paidTotal,
                             long activeTotal, long outstanding, String unresolved) {
        // find the order that holds this payment
        Long orderId = jdbc.queryForObject("SELECT order_id FROM payments WHERE id = ?", Long.class, paymentId);
        Q4Row row = rows.get(orderId);
        assertEquals(totalDue, row.totalDue(), "total_due of order holding p" + paymentId);
        assertEquals(paidTotal, row.paidTotal(), "paid_total of order holding p" + paymentId);
        assertEquals(activeTotal, row.activeTotal(), "active reservation of order holding p" + paymentId);
        assertEquals(outstanding, row.outstanding(), "outstanding of order holding p" + paymentId);
        assertEquals(unresolved, row.unresolved(), "unresolved Xendit string of order holding p" + paymentId);
    }

    // =====================================================================
    // Q5 — in-flight idempotency keys
    // =====================================================================

    @Test
    void q5_inFlightIdempotencyKeys_returnOnlyOldKeys() {
        UUID oldInFlight = seedIdempotencyKey(oldTime, null);
        UUID recentInFlight = seedIdempotencyKey(recentTime, null);
        UUID completedKey = seedIdempotencyKey(oldTime, 201);

        List<UUID> returned = jdbc.query(executable(Q5), (rs, i) -> UUID.fromString(rs.getString("key")), cutoffTs);
        assertEquals(List.of(oldInFlight), returned, "only the old in-flight key is returned");
        assertTrue(!returned.contains(recentInFlight), "recent in-flight key is below threshold");
        assertTrue(!returned.contains(completedKey), "completed key is never in-flight");

        assertEquals("PAYMENT_CREATE", jdbc.queryForObject(
                "SELECT scope FROM idempotency_keys WHERE key = ?", String.class, oldInFlight));
    }

    // =====================================================================
    // Q6 — queryable candidates for read-only provider verification
    // =====================================================================

    @Test
    void q6_queryableCandidates_fullColumnsForProviderVerification() {
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.query(executable(Q6), rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider_request_id", rs.getString("provider_request_id"));
            row.put("charge_state", rs.getString("charge_state"));
            rows.put(rs.getLong("id"), row);
        }, cutoffTs, 10);
        assertEquals(List.of(p4, p5), List.copyOf(rows.keySet()),
                "Q6 lists the same candidates as Q3, oldest first");
        assertEquals(requestP4, rows.get(p4).get("provider_request_id"));
        assertEquals("ACCEPTED", rows.get(p4).get("charge_state"));
        assertEquals(requestP5, rows.get(p5).get("provider_request_id"));
        assertEquals("AMBIGUOUS", rows.get(p5).get("charge_state"));
        // CANCELED cannot be detected locally: no fixture row is claimed to be CANCELED
        assertTrue(rows.values().stream().noneMatch(r -> "CANCELED".equals(r.get("charge_state"))));
    }

    // =====================================================================
    // Documentation consistency — ADR-023 SQL blocks == test constants
    // =====================================================================

    @Test
    void decisionsRunbookSqlBlocks_matchTestConstantsExactly() throws Exception {
        Path docs = resolveDecisionsDoc();
        String markdown = Files.readString(docs, StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> blocks = sqlBlocks(markdown);
        assertTrue(blocks.size() >= 6, "docs/DECISIONS.md must contain the six runbook ```sql blocks");

        for (int q = 1; q <= 6; q++) {
            String marker = "-- Q" + q + ":";
            List<String> matching = blocks.stream().filter(b -> b.startsWith(marker)).toList();
            assertEquals(1, matching.size(), "exactly one ```sql block marked " + marker + " in docs/DECISIONS.md");
            assertEquals(normalize(constant(q)), normalize(matching.get(0)),
                    "ADR-023 SQL block Q" + q + " must be identical to the test constant");
        }
    }

    /** Walks up from the Surefire working directory until docs/DECISIONS.md is found; fails loudly otherwise. */
    private static Path resolveDecisionsDoc() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs").resolve("DECISIONS.md");
            if (Files.exists(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "docs/DECISIONS.md could not be resolved from working directory: "
                        + System.getProperty("user.dir") + " — refusing to skip the documentation consistency check");
    }

    private static List<String> sqlBlocks(String markdown) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = Pattern.compile("```sql\\n(.*?)\\n```", Pattern.DOTALL).matcher(markdown);
        while (matcher.find()) blocks.add(matcher.group(1));
        return blocks;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String constant(int q) {
        return switch (q) {
            case 1 -> Q1;
            case 2 -> Q2;
            case 3 -> Q3;
            case 4 -> Q4;
            case 5 -> Q5;
            case 6 -> Q6;
            default -> throw new IllegalArgumentException("no such runbook query: Q" + q);
        };
    }
}
