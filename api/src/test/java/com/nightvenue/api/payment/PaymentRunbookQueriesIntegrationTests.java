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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
              AND (charge_started_at IS NULL OR charge_started_at < now() - interval '1 hour')
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
              AND (charge_started_at IS NULL OR charge_started_at < now() - interval '1 hour')
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
              AND charge_started_at < now() - interval '1 hour'
            ORDER BY charge_started_at ASC, id ASC
            LIMIT 50;
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
            LEFT JOIN (
                SELECT order_id, SUM(amount) AS active_total
                FROM payments
                WHERE status IN ('PENDING', 'PAID')
                GROUP BY order_id
            ) a ON a.order_id = o.id
            LEFT JOIN (
                SELECT order_id, string_agg(id::text || ':' || charge_state, ', ' ORDER BY id) AS unresolved_xendit
                FROM payments
                WHERE provider = 'XENDIT'
                  AND method = 'QRIS'
                  AND status = 'PENDING'
                GROUP BY order_id
            ) x ON x.order_id = o.id
            ORDER BY o.id ASC;
            """;

    private static final String Q5 = """
            -- Q5: in-flight idempotency keys (response_status IS NULL, threshold = now - min_age)
            SELECT key, scope, user_id, request_hash, created_at
            FROM idempotency_keys
            WHERE response_status IS NULL
              AND created_at < now() - interval '1 hour'
            ORDER BY created_at ASC;
            """;

    private static final String Q6 = """
            -- Q6: queryable PENDING Xendit candidates for read-only provider Status API verification
            -- Note: Provider CANCELED is only visible through the provider Status API, not through this query.
            SELECT id, provider_request_id, charge_started_at, charge_state
            FROM payments
            WHERE provider = 'XENDIT'
              AND method = 'QRIS'
              AND status = 'PENDING'
              AND provider_request_id IS NOT NULL
            ORDER BY charge_started_at ASC NULLS LAST, id ASC;
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
    private final List<UUID> keys = new ArrayList<>();

    // fixture payment ids (filled in seedFixtures)
    private long p1, p2, p3, p4, p5, p6, p7, p8, p9, p10;
    private String requestP4;
    private String requestP5;

    private final Set<Long> seededPaymentIds = new HashSet<>();
    private final Set<Long> seededOrderIds = new HashSet<>();

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

        seededPaymentIds.addAll(List.of(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10));
    }

    // ---------- fixture helpers ----------

    private long createOrder(long totalDue) {
        orderCounter++;
        Timestamp now = Timestamp.from(Instant.now());
        long orderId = jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', ?, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, venueId, fixture + "-O" + orderCounter, cashierId, totalDue, totalDue, now, now, now);
        seededOrderIds.add(orderId);
        return orderId;
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

    /** Strips the doc-marker comment lines and trailing semicolon for JDBC execution. */
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

    private List<Long> runIds(String sql) {
        return jdbc.query(executable(sql), (rs, i) -> rs.getLong(1));
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
        });
        return rows;
    }

    private List<UUID> runQ5Keys() {
        return jdbc.query(executable(Q5), (rs, i) -> UUID.fromString(rs.getString("key")));
    }

    private List<Long> runQ6Ids() {
        return jdbc.query(executable(Q6), (rs, i) -> rs.getLong("id"));
    }

    // =====================================================================
    // Q1 — stuck SENDING / NOT_SENT
    // =====================================================================

    @Test
    void q1_stuckSendingAndNotSent_returnsOnlyOldRows() {
        List<Long> allResults = runIds(Q1);
        List<Long> seededResults = allResults.stream().filter(seededPaymentIds::contains).toList();
        assertEquals(List.of(p1, p2), seededResults,
                "Q1 must return exactly the old SENDING and old NOT_SENT fixtures");
        assertFalse(allResults.contains(p8), "recent SENDING below threshold is excluded");
        assertFalse(allResults.contains(p3) || allResults.contains(p10), "AMBIGUOUS rows are not SENDING/NOT_SENT");
        assertFalse(allResults.contains(p7), "FAILED row is excluded");
        assertFalse(allResults.contains(p6) || allResults.contains(p9), "unrelated CASH rows are excluded");
    }

    // =====================================================================
    // Q2 — AMBIGUOUS without provider_request_id
    // =====================================================================

    @Test
    void q2_ambiguousWithoutRequestId_returnsOnlyOldUnqueryableRow() {
        List<Long> allResults = runIds(Q2);
        List<Long> seededResults = allResults.stream().filter(seededPaymentIds::contains).toList();
        assertEquals(List.of(p3), seededResults,
                "Q2 must return exactly the old AMBIGUOUS row without a provider request id");
        assertFalse(allResults.contains(p10), "recent AMBIGUOUS below threshold is excluded");
        assertFalse(allResults.contains(p5), "AMBIGUOUS with a request id is queryable and excluded");
        assertFalse(allResults.contains(p7), "FAILED row is excluded");
        assertFalse(allResults.contains(p1) || allResults.contains(p2), "SENDING/NOT_SENT rows are not AMBIGUOUS");
    }

    // =====================================================================
    // Q3 — reconciliation candidates (production semantics)
    // =====================================================================

    @Test
    void q3_reconciliationCandidates_mirrorProductionSemantics() {
        List<Long> allResults = runIds(Q3);
        List<Long> seededResults = allResults.stream().filter(seededPaymentIds::contains).toList();
        assertEquals(List.of(p4, p5), seededResults,
                "Q3 must return exactly the old ACCEPTED and old AMBIGUOUS rows that carry a request id");
        assertFalse(allResults.contains(p1) || allResults.contains(p2), "SENDING/NOT_SENT are not reconciliation candidates");
        assertFalse(allResults.contains(p3) || allResults.contains(p10), "AMBIGUOUS without request id is not a reconciliation candidate");
        assertFalse(allResults.contains(p8), "recent SENDING is excluded");
        assertFalse(allResults.contains(p7), "FAILED row is excluded");
        assertFalse(allResults.contains(p6) || allResults.contains(p9), "CASH rows are excluded");
    }

    // =====================================================================
    // Q4 — orders with active payment reservations (BR-28 / BR-20)
    // =====================================================================

    @Test
    void q4_orderActiveReservations_matchBr28AndBr20Evidence() {
        Map<Long, Q4Row> rows = runQ4();
        Map<Long, Q4Row> seededRows = rows.entrySet().stream()
                .filter(e -> seededOrderIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

        assertEquals(10, seededRows.size(), "all 10 seeded orders of the venue are listed exactly once");

        seededRows.values().forEach(r -> {
            assertEquals(venueId, r.venueId());
            assertEquals("OPEN", r.orderStatus());
        });

        assertQ4Row(seededRows, p1, 1_000_000, 0, 300_000, 700_000, p1 + ":SENDING");
        assertQ4Row(seededRows, p2, 900_000, 0, 200_000, 700_000, p2 + ":NOT_SENT");
        assertQ4Row(seededRows, p3, 700_000, 0, 150_000, 550_000, p3 + ":AMBIGUOUS");
        assertQ4Row(seededRows, p4, 800_000, 0, 500_000, 300_000, p4 + ":ACCEPTED");
        assertQ4Row(seededRows, p5, 600_000, 0, 250_000, 350_000, p5 + ":AMBIGUOUS");
        assertQ4Row(seededRows, p6, 400_000, 100_000, 100_000, 300_000, "");
        assertQ4Row(seededRows, p7, 850_000, 0, 0, 850_000, "");
        assertQ4Row(seededRows, p8, 300_000, 0, 50_000, 250_000, p8 + ":SENDING");
        assertQ4Row(seededRows, p9, 500_000, 75_000, 75_000, 425_000, "");
        assertQ4Row(seededRows, p10, 400_000, 0, 60_000, 340_000, p10 + ":AMBIGUOUS");
    }

    private void assertQ4Row(Map<Long, Q4Row> seededRows, long paymentId, long totalDue, long paidTotal,
                             long activeTotal, long outstanding, String unresolved) {
        Long orderId = jdbc.queryForObject("SELECT order_id FROM payments WHERE id = ?", Long.class, paymentId);
        Q4Row row = seededRows.get(orderId);
        assertNotNull(row, "order holding p" + paymentId + " must be present in Q4 results");
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
        Set<UUID> testKeys = Set.of(oldInFlight, recentInFlight, completedKey);

        List<UUID> allResults = runQ5Keys();
        List<UUID> seededResults = allResults.stream().filter(testKeys::contains).toList();
        assertEquals(List.of(oldInFlight), seededResults, "only the old in-flight key is returned");
        assertFalse(allResults.contains(recentInFlight), "recent in-flight key is below threshold");
        assertFalse(allResults.contains(completedKey), "completed key is never in-flight");

        assertEquals("PAYMENT_CREATE", jdbc.queryForObject(
                "SELECT scope FROM idempotency_keys WHERE key = ?", String.class, oldInFlight));
    }

    // =====================================================================
    // Q6 — queryable candidates for read-only provider verification
    // =====================================================================

    @Test
    void q6_queryableCandidates_fullColumnsForProviderVerification() {
        List<Long> allResults = runQ6Ids();
        List<Long> seededResults = allResults.stream().filter(seededPaymentIds::contains).toList();
        assertEquals(List.of(p4, p5), seededResults,
                "Q6 lists the candidates with provider_request_id, oldest first");
        assertFalse(allResults.contains(p1) || allResults.contains(p2) || allResults.contains(p3)
                || allResults.contains(p8) || allResults.contains(p10), "rows without provider_request_id are excluded");
    }

    // =====================================================================
    // Documentation consistency — ADR-023 SQL blocks == test constants
    // =====================================================================

    @Test
    void decisionsRunbookSqlBlocks_matchTestConstantsExactly() throws Exception {
        Path docs = resolveDecisionsDoc();
        String markdown = Files.readString(docs, StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> blocks = sqlBlocks(markdown);
        assertTrue(blocks.size() >= 6, "docs/DECISIONS.md must contain at least six runbook ```sql blocks");

        for (int q = 1; q <= 6; q++) {
            String marker = "-- Q" + q + ":";
            List<String> matching = blocks.stream().filter(b -> b.startsWith(marker)).toList();
            assertEquals(1, matching.size(), "exactly one ```sql block marked " + marker + " in docs/DECISIONS.md");
            assertEquals(normalize(constant(q)), normalize(matching.get(0)),
                    "ADR-023 SQL block Q" + q + " must be identical to the test constant");
        }
    }

    @Test
    void documentedQueries_areSelectOnlyAndContainNoForbiddenTokens() {
        List<String> forbidden = List.of("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE");
        for (int q = 1; q <= 6; q++) {
            String sql = executable(constant(q)); // strips comment lines
            String upper = sql.toUpperCase();
            assertTrue(upper.startsWith("SELECT") || upper.startsWith("WITH"),
                    "Q" + q + " must start with SELECT or WITH");
            for (String token : forbidden) {
                assertFalse(upper.contains(token),
                        "Q" + q + " contains forbidden mutating token: " + token);
            }
        }
    }

    /** Walks up from System.getProperty("user.dir") until docs/DECISIONS.md is found; fails loudly otherwise. */
    private static Path resolveDecisionsDoc() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        int levels = 0;
        while (dir != null && levels <= 5) {
            Path candidate = dir.resolve("docs").resolve("DECISIONS.md");
            if (Files.exists(candidate)) return candidate;
            dir = dir.getParent();
            levels++;
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
