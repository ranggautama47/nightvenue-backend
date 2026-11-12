package com.nightvenue.api.payment;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.payment.reconciliation.PaymentReconciliationService;
import com.nightvenue.api.shift.ShiftRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.oauth2.jwt.JwsHeader.with;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5-A deterministic concurrency tests. Deliberately NOT transactional: TX1/TX2,
 * webhook and reconciliation boundaries must commit for real. Every test cleans
 * its own rows. No production hooks: the QRIS Create hold and the Status-API
 * arrival gate live entirely in the test-local {@link ConcurrencyXenditStub}.
 */
@SpringBootTest(properties = {
        "nightvenue.seed.enabled=false",
        "nightvenue.payment.xendit.secret-key=test-secret-not-real",
        "nightvenue.payment.xendit.read-timeout-ms=30000",
        "nightvenue.payment.reconciliation.enabled=false",
        "nightvenue.payment.reconciliation.min-age=PT1H",
        "nightvenue.payment.reconciliation.batch-size=50",
        "nightvenue.payment.reconciliation.max-per-cycle=50",
        "nightvenue.payment.reconciliation.fixed-delay=PT1H"
})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, PaymentConcurrencyIntegrationTests.ClockConfig.class})
class PaymentConcurrencyIntegrationTests {
    private static final String API = "/api/v1/orders";
    private static final String WEBHOOK = "/api/v1/webhooks/xendit";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Jakarta");
    private static final int CUTOFF = 6;
    private static final long POLL_TIMEOUT_SECONDS = 20;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ObjectMapper mapper;
    @Autowired BusinessDateService businessDates;
    @Autowired ShiftRepository shiftRepository;
    @Autowired PaymentReconciliationService reconciler;
    @Autowired MutableClock clock;
    @Autowired DataSource dataSource;

    private String fixture;
    private long venueId, cashierId, managerId;
    private int orderCounter;
    private final List<UUID> keys = new ArrayList<>();

    @DynamicPropertySource
    static void xenditBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("nightvenue.payment.xendit.base-url", () -> "http://localhost:" + ConcurrencyXenditStub.port());
    }

    @BeforeEach
    void setup() {
        clock.set(Instant.parse("2026-01-01T02:00:00Z"));
        fixture = "M5A-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        cashierId = createUser("CASHIER", "-c");
        managerId = createUser("MANAGER", "-m");
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,tax_rate_bps,
                                    service_charge_bps,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, 0, 0, true, ?, ?) RETURNING id
                """, Long.class, fixture, fixture, now, now);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueId);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", managerId, venueId);
        ConcurrencyXenditStub.reset();
    }

    @AfterEach
    void cleanup() {
        if (fixture == null) return;
        keys.forEach(key -> jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key));
        jdbc.update("DELETE FROM payment_events WHERE payment_id IN (SELECT id FROM payments WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM payments WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE venue_id = ?)", venueId);
        jdbc.update("""
                DELETE FROM inventory_transactions WHERE inventory_item_id IN (
                    SELECT ii.id FROM inventory_items ii JOIN stock_locations sl ON sl.id = ii.location_id
                    WHERE sl.venue_id = ?)
                """, venueId);
        jdbc.update("DELETE FROM orders WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM inventory_items WHERE location_id IN (SELECT id FROM stock_locations WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM stock_locations WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM recipe_items WHERE recipe_id IN (SELECT id FROM recipes WHERE product_id IN (SELECT id FROM products WHERE venue_id = ?))", venueId);
        jdbc.update("DELETE FROM recipes WHERE product_id IN (SELECT id FROM products WHERE venue_id = ?)", venueId);
        jdbc.update("DELETE FROM products WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM cashier_shifts WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM venue_tables WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM user_venues WHERE venue_id = ?", venueId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", cashierId, managerId);
        jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
    }

    // ---------- Phase-0 table constants (literal source strings) ----------
    private static final String AMOUNT_EXCEEDS = "Amount exceeds outstanding balance";
    private static final String SHIFT_NOT_OPEN = "SHIFT_NOT_OPEN";

    // =====================================================================
    // T1a — QRIS + CASH overshoot: exactly one 201, loser 422
    // =====================================================================

    @Test
    void t1a_qrisPlusCashOvershoot_exactlyOneReservationLoser422() throws Exception {
        long orderId = createOrder(2_000_000, 4_000_000);
        seedShift();

        UUID cashKey = key();
        UUID qrisKey = key();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> cash = executor.submit((Callable<MvcResult>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(post(API + "/" + orderId + "/payments")
                                .header("Authorization", bearer(cashierId, "CASHIER"))
                                .header("Idempotency-Key", cashKey.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"method\":\"CASH\",\"amount\":3000000}"))
                        .andReturn();
            });
            Future<MvcResult> qris = executor.submit((Callable<MvcResult>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(post(API + "/" + orderId + "/payments")
                                .header("Authorization", bearer(cashierId, "CASHIER"))
                                .header("Idempotency-Key", qrisKey.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"method\":\"QRIS\",\"amount\":3000000}"))
                        .andReturn();
            });

            MvcResult cashResult = cash.get(60, TimeUnit.SECONDS);
            MvcResult qrisResult = qris.get(60, TimeUnit.SECONDS);
            int cashStatus = cashResult.getResponse().getStatus();
            int qrisStatus = qrisResult.getResponse().getStatus();

            assertTrue((cashStatus == 201 && qrisStatus == 422) || (cashStatus == 422 && qrisStatus == 201),
                    "exactly one 201 and one 422, got CASH=" + cashStatus + " QRIS=" + qrisStatus);
            MvcResult loser = cashStatus == 422 ? cashResult : qrisResult;
            assertEquals(AMOUNT_EXCEEDS, jsonDetail(loser),
                    "loser detail must be the exact BR-28 code");

            // one payment row only; reservation invariant
            assertEquals(1, paymentCount(orderId), "exactly one payment row");
            long reserved = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount), 0) FROM payments
                    WHERE order_id = ? AND status IN ('PENDING','PAID')
                    """, Long.class, orderId);
            assertEquals(3_000_000L, reserved, "reserved amount equals the winner's amount");
            assertTrue(reserved <= 4_000_000L, "SUM(PAID)+SUM(PENDING) <= total_due");

            String method = jdbc.queryForObject("SELECT method FROM payments WHERE order_id = ?", String.class, orderId);
            if ("CASH".equals(method)) {
                // CASH won: PAID + PARTIALLY_PAID, no provider call
                assertEquals("PAID", paymentStatus(orderId));
                assertEquals("PARTIALLY_PAID", orderStatus(orderId));
                assertEquals(3_000_000L, paidTotal(orderId));
                assertEquals(0, ConcurrencyXenditStub.createCount.get(), "no Xendit Create call when CASH won");
            } else {
                // QRIS won: PENDING + OPEN, one Create call
                assertEquals("PENDING", paymentStatus(orderId));
                assertEquals("OPEN", orderStatus(orderId));
                assertEquals(0L, paidTotal(orderId));
                assertEquals(1, ConcurrencyXenditStub.createCount.get(), "one Xendit Create call when QRIS won");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // =====================================================================
    // T1b — exact boundary: both 201, then 1 rupiah rejected 422
    // =====================================================================

    @Test
    void t1b_exactBoundary_both201_thenOneRupiah422() throws Exception {
        long orderId = createOrder(2_000_000, 4_000_000);
        seedShift();

        UUID cashKey = key();
        UUID qrisKey = key();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> cash = executor.submit((Callable<MvcResult>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(post(API + "/" + orderId + "/payments")
                                .header("Authorization", bearer(cashierId, "CASHIER"))
                                .header("Idempotency-Key", cashKey.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"method\":\"CASH\",\"amount\":2000000}"))
                        .andReturn();
            });
            Future<MvcResult> qris = executor.submit((Callable<MvcResult>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(post(API + "/" + orderId + "/payments")
                                .header("Authorization", bearer(cashierId, "CASHIER"))
                                .header("Idempotency-Key", qrisKey.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"method\":\"QRIS\",\"amount\":2000000}"))
                        .andReturn();
            });

            MvcResult cashResult = cash.get(60, TimeUnit.SECONDS);
            MvcResult qrisResult = qris.get(60, TimeUnit.SECONDS);
            assertEquals(201, cashResult.getResponse().getStatus(), "CASH at exact boundary succeeds");
            assertEquals(201, qrisResult.getResponse().getStatus(), "QRIS at exact boundary succeeds");

            long reserved = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount), 0) FROM payments
                    WHERE order_id = ? AND status IN ('PENDING','PAID')
                    """, Long.class, orderId);
            assertEquals(4_000_000L, reserved, "SUM(PAID)+SUM(PENDING) == total_due");
            assertEquals("PAID", paymentStatus(orderId, "CASH"));
            assertEquals("PENDING", paymentStatus(orderId, "QRIS"));
            assertEquals("PARTIALLY_PAID", orderStatus(orderId));
            assertEquals(2_000_000L, paidTotal(orderId));
        } finally {
            executor.shutdownNow();
        }

        // further 1 rupiah is rejected
        UUID extraKey = key();
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", extraKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(AMOUNT_EXCEEDS));
        assertEquals(2, paymentCount(orderId), "payment row count unchanged after 422");
    }

    // =====================================================================
    // T2 — add-items vs payment, deterministic lock observation
    // =====================================================================

    @Test
    void t2_addItemsVsPayment_bothBlockOnOrderLock_oneValidSerialOutcome() throws Exception {
        // fixture: product 1,000,000 with recipe (5 ML per unit); stock 100,000 ML
        long productId = seedProductWithRecipe();
        long orderId = createOrder(2_000_000, 2_000_000);
        // seed an existing 2,000,000 order item so add-items recalculates subtotal 2M+1M=3M
        seedOrderItem(orderId, productId, 2_000_000);
        seedShift();

        // hold the order row on a separate connection
        Connection holder = dataSource.getConnection();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<MvcResult> payment = null;
        Future<MvcResult> items = null;
        long holderPid;
        try {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                holderPid = rs.getLong(1);
            }
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, orderId);
                try (ResultSet rs = lock.executeQuery()) {
                    assertTrue(rs.next(), "order row exists to lock");
                }
            }

            UUID cashKey = key();
            UUID itemsKey = key();
            CyclicBarrier barrier = new CyclicBarrier(2);
            try {
                payment = executor.submit((Callable<MvcResult>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return mvc.perform(post(API + "/" + orderId + "/payments")
                                    .header("Authorization", bearer(cashierId, "CASHIER"))
                                    .header("Idempotency-Key", cashKey.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"method\":\"CASH\",\"amount\":2000000}"))
                            .andReturn();
                });
                items = executor.submit((Callable<MvcResult>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return mvc.perform(post(API + "/" + orderId + "/items")
                                    .header("Authorization", bearer(cashierId, "CASHIER"))
                                    .header("Idempotency-Key", itemsKey.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                            .andReturn();
                });

                // bounded poll: BOTH application sessions must be waiting on the held row lock
                boolean bothBlocked = waitForBlockedSessions(holderPid, 2, POLL_TIMEOUT_SECONDS);
                List<String> diag = bothBlocked ? List.of() : sessionDiagnostics();

                // release the held row FIRST — workers must be able to finish on every path
                holder.rollback();
                if (!bothBlocked) {
                    fail("both payment and add-items workers must be blocked on order " + orderId
                            + " (holder pid " + holderPid + "); sessions: " + diag);
                }
            } finally {
                holder.rollback(); // no-op when the transaction was already released above
            }

            MvcResult paymentResult = payment.get(60, TimeUnit.SECONDS);
            MvcResult itemsResult = items.get(60, TimeUnit.SECONDS);
            int paymentStatus = paymentResult.getResponse().getStatus();
            int itemsStatus = itemsResult.getResponse().getStatus();

            // invariant: never PAID with paid_total < total_due
            String orderStatus = orderStatus(orderId);
            long paidTotal = paidTotal(orderId);
            long totalDue = totalDue(orderId);
            assertTrue(!("PAID".equals(orderStatus) && paidTotal < totalDue),
                    "order must not be PAID with paid_total < total_due");
            long reserved = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount), 0) FROM payments
                    WHERE order_id = ? AND status IN ('PENDING','PAID')
                    """, Long.class, orderId);
            assertTrue(reserved <= totalDue, "SUM(PAID)+SUM(PENDING) <= total_due");
            assertEquals(1, paymentCount(orderId), "exactly one payment row");

            BigDecimal stock = stockQuantity();
            if (paymentStatus == 201 && itemsStatus == 201) {
                // (b) add-items first: total grows, payment partial, stock deducted once
                assertEquals(3_000_000L, totalDue, "total_due grew to 3,000,000");
                assertEquals("PARTIALLY_PAID", orderStatus);
                assertEquals(2_000_000L, paidTotal);
                assertEquals(0, stock.compareTo(new BigDecimal("99995")), "stock deducted exactly once (5 ML)");
            } else if (paymentStatus == 201 && itemsStatus == 409) {
                // (a) payment first: order PAID, add-items rejected with exact code
                assertEquals("PAID", orderStatus);
                assertEquals(2_000_000L, paidTotal);
                assertEquals(2_000_000L, totalDue);
                assertEquals("Order does not accept items: status is PAID", jsonDetail(itemsResult));
                assertEquals(0, stock.compareTo(new BigDecimal("100000")), "no stock deducted for rejected add-items");
            } else if (paymentStatus == 409 && itemsStatus == 201) {
                // (a reversed) add-items first then payment... invalid: payment must fit, assert not possible
                fail("CASH 2,000,000 on total_due >= 2,000,000 must never be 409");
            } else {
                fail("unexpected outcome pair: payment=" + paymentStatus + " items=" + itemsStatus);
            }
        } finally {
            try { holder.rollback(); } catch (Exception ignored) { }
            awaitQuietly(payment);
            awaitQuietly(items);
            executor.shutdownNow();
            try { holder.setAutoCommit(true); } catch (Exception ignored) { }
            try { holder.close(); } catch (Exception ignored) { }
        }
    }

    // =====================================================================
    // T3 — partial QRIS settlement via real webhook (two payments)
    // =====================================================================

    @Test
    void t3_partialThenFullQrisSettlementViaWebhook() throws Exception {
        long tableId = seedTable();
        long orderId = createOrder(5_000_000, 5_000_000);
        jdbc.update("UPDATE orders SET table_id = ? WHERE id = ?", tableId, orderId);
        jdbc.update("UPDATE venue_tables SET status = 'OCCUPIED' WHERE id = ?", tableId);

        long p1 = seedQrisPayment(orderId, 3_000_000, "pr-m5a-t3-a");
        long p2 = seedQrisPayment(orderId, 2_000_000, "pr-m5a-t3-b");

        // ---- step 1: settle the 3,000,000 payment -> PARTIALLY_PAID ----
        ConcurrencyXenditStub.setStatusContext(referenceOf(p1), 3_000_000);
        webhookCapture("pr-m5a-t3-a", referenceOf(p1), 3_000_000, "pay-m5a-1")
                .andExpect(status().isOk());
        assertEquals("PAID", paymentStatusById(p1));
        assertEquals("PARTIALLY_PAID", orderStatus(orderId));
        assertEquals(3_000_000L, paidTotal(orderId));
        assertNull(jdbc.queryForObject("SELECT closed_at FROM orders WHERE id = ?", Timestamp.class, orderId),
                "closed_at stays NULL on partial settlement");
        assertEquals("OCCUPIED", tableStatus(tableId), "table NOT released on partial settlement");
        assertEquals(1, eventCount(p1, "PROCESSED"), "one PROCESSED event for the first capture");
        assertEquals("payment.capture:pr-m5a-t3-a:pay-m5a-1", eventKey(p1, "PROCESSED"));

        // ---- step 2: settle the 2,000,000 payment -> PAID, table released ----
        ConcurrencyXenditStub.setStatusContext(referenceOf(p2), 2_000_000);
        webhookCapture("pr-m5a-t3-b", referenceOf(p2), 2_000_000, "pay-m5a-2")
                .andExpect(status().isOk());
        assertEquals("PAID", paymentStatusById(p2));
        assertEquals("PAID", orderStatus(orderId));
        assertEquals(5_000_000L, paidTotal(orderId));
        assertNotNull(jdbc.queryForObject("SELECT closed_at FROM orders WHERE id = ?", Timestamp.class, orderId),
                "closed_at set when fully paid");
        assertEquals("AVAILABLE", tableStatus(tableId), "table released when fully paid");
        assertEquals(1, eventCount(p2, "PROCESSED"), "one PROCESSED event for the second capture");
        assertEquals("payment.capture:pr-m5a-t3-b:pay-m5a-2", eventKey(p2, "PROCESSED"));

        // audit: exact action names, no anomalies
        assertEquals(1, auditCount(p1, "PAYMENT_SETTLED"), "one PAYMENT_SETTLED for p1");
        assertEquals(1, auditCount(p2, "PAYMENT_SETTLED"), "one PAYMENT_SETTLED for p2");
        assertEquals(1, auditCount(p1, "PAYMENT_STATUS_CHANGED"), "one PAYMENT_STATUS_CHANGED for p1");
        assertEquals(1, auditCount(p2, "PAYMENT_STATUS_CHANGED"), "one PAYMENT_STATUS_CHANGED for p2");
        assertEquals(0, anomalyCount(orderId), "no PAYMENT_OVERPAID or other anomaly");
    }

    // =====================================================================
    // T4 — webhook racing reconciliation (arrival-order Status gate)
    // =====================================================================

    @Test
    void t4_webhookRacesReconciliation_settlesExactlyOnce() throws Exception {
        long orderId = createOrder(10_000_000, 10_000_000);
        // candidate per Phase 0 item 5: PENDING + ACCEPTED + provider_request_id + charge_started_at older than minAge
        long paymentId = seedReconciliationCandidate(orderId, 10_000_000, "pr-m5a-t4");
        String reference = referenceOf(paymentId);

        ConcurrencyXenditStub.setStatusPaymentId("pay-m5a-t4");
        ConcurrencyXenditStub.armStatusGate(reference, 10_000_000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> reconcile = executor.submit(() -> reconciler.reconcile());

            // 1) reconcile blocks in the held Status GET
            assertTrue(ConcurrencyXenditStub.statusFirstArrived.await(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "reconciliation Status GET must arrive at the stub");

            // 2) real webhook settles while reconcile is held (its GET answers immediately)
            webhookCapture("pr-m5a-t4", reference, 10_000_000, "pay-m5a-t4")
                    .andExpect(status().isOk());
            assertEquals("PAID", paymentStatusById(paymentId), "webhook settled the payment");

            // 3) release the held GET; reconcile sees PAID under the lock
            ConcurrencyXenditStub.releaseStatusGate();
            int inspected = reconcile.get(60, TimeUnit.SECONDS);
            assertEquals(1, inspected, "one candidate inspected");

            // invariants
            assertEquals("PAID", paymentStatusById(paymentId), "payment PAID once");
            assertEquals(10_000_000L, paidTotal(orderId), "paid_total not doubled");
            assertEquals("PAID", orderStatus(orderId));
            assertEquals(1, auditCount(paymentId, "PAYMENT_SETTLED"), "exactly one PAYMENT_SETTLED audit");
            assertEquals(0, anomalyCount(orderId), "no false anomaly");
            assertEquals(1, eventCount(paymentId, "PROCESSED"), "webhook event PROCESSED");
            assertEquals("payment.capture:pr-m5a-t4:pay-m5a-t4", eventKey(paymentId, "PROCESSED"));
            // two event rows are legitimate (different keys); reconcile row is IGNORED_STALE per source gate
            assertEquals(2, totalEventCount(paymentId), "webhook + reconcile event rows coexist");
            assertEquals(1, eventCount(paymentId, "IGNORED_STALE"), "reconcile event IGNORED_STALE");
            assertEquals("reconcile:SUCCEEDED:pr-m5a-t4:pay-m5a-t4", eventKey(paymentId, "IGNORED_STALE"));
        } finally {
            ConcurrencyXenditStub.releaseStatusGate();
            executor.shutdownNow();
        }
    }

    // =====================================================================
    // T5 — real webhook racing QRIS TX2 (Create held by latch)
    // =====================================================================

    @Test
    void t5_webhookDuringCreateWindow_tx2Fenced409() throws Exception {
        long orderId = createOrder(10_000_000, 10_000_000);
        ConcurrencyXenditStub.armCreateHold();
        UUID k1 = key();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection unused = null;
        try {
            Future<MvcResult> create = executor.submit((Callable<MvcResult>) () ->
                    mvc.perform(post(API + "/" + orderId + "/payments")
                            .header("Authorization", bearer(cashierId, "CASHIER"))
                            .header("Idempotency-Key", k1.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"method\":\"QRIS\",\"amount\":10000000}"))
                            .andReturn());

            // 1) Create arrives at the stub: proves TX1 committed (SENDING row, request id NULL)
            assertTrue(ConcurrencyXenditStub.createArrived.await(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Create POST must arrive at the stub after TX1 commit");
            String createBody = ConcurrencyXenditStub.lastCreateBody;
            assertNotNull(createBody, "captured Create body");
            String reference = jsonField(createBody, "reference_id");
            assertTrue(reference.startsWith("nv_pay_"), "reference_id in real nv_pay_<id> format: " + reference);
            long paymentId = Long.parseLong(reference.substring("nv_pay_".length()));
            assertEquals("SENDING", chargeStateById(paymentId), "TX1 committed SENDING row");
            assertNull(jdbc.queryForObject("SELECT provider_request_id FROM payments WHERE id = ?",
                    String.class, paymentId), "provider_request_id NULL after TX1");

            // 2) real webhook settles the SENDING row (Status GET answers immediately)
            ConcurrencyXenditStub.setStatusContext(reference, 10_000_000);
            webhookCapture(ConcurrencyXenditStub.PR_ID, reference, 10_000_000, "pay-m5a-t5")
                    .andExpect(status().isOk());
            assertEquals("PAID", paymentStatusById(paymentId), "webhook recovered SENDING -> PAID");
            assertEquals("AMBIGUOUS", chargeStateById(paymentId), "SENDING recovery writes AMBIGUOUS, never ACCEPTED");
            assertEquals(ConcurrencyXenditStub.PR_ID,
                    jdbc.queryForObject("SELECT provider_request_id FROM payments WHERE id = ?", String.class, paymentId));

            // 3) release the Create response; TX2 must fence (source-confirmed 409 PAYMENT_STATE_CONFLICT)
            ConcurrencyXenditStub.releaseCreateHold();
            MvcResult createResult = create.get(60, TimeUnit.SECONDS);
            assertEquals(409, createResult.getResponse().getStatus(), "TX2 fenced with 409");
            assertEquals("PAYMENT_STATE_CONFLICT", jsonDetail(createResult));

            // 4) payment unchanged by TX2; idempotency key NOT completed
            assertEquals("PAID", paymentStatusById(paymentId), "payment still PAID after fenced TX2");
            assertEquals("AMBIGUOUS", chargeStateById(paymentId), "charge_state never overwritten to ACCEPTED");
            assertNull(jdbc.queryForObject("SELECT qr_payload FROM payments WHERE id = ?", String.class, paymentId),
                    "qr_payload never written");
            assertEquals(ConcurrencyXenditStub.PR_ID,
                    jdbc.queryForObject("SELECT provider_request_id FROM payments WHERE id = ?", String.class, paymentId));
            assertEquals(10_000_000L, paidTotal(orderId), "paid_total not doubled");
            assertEquals("PAID", orderStatus(orderId));
            assertEquals(1, totalEventCount(paymentId), "exactly one payment_events row");
            assertEquals(1, eventCount(paymentId, "PROCESSED"), "the webhook event");
            assertEquals(1, auditCount(paymentId, "PAYMENT_SETTLED"), "one PAYMENT_SETTLED audit");
            Integer snapshotStatus = jdbc.queryForObject(
                    "SELECT response_status FROM idempotency_keys WHERE key = ?", Integer.class, k1);
            assertNull(snapshotStatus, "idempotency key NOT completed by fenced TX2");

            // 5) replay of the same key -> 409 PAYMENT_IN_PROGRESS + Retry-After: 3 (accepted S2 limitation)
            mvc.perform(post(API + "/" + orderId + "/payments")
                            .header("Authorization", bearer(cashierId, "CASHIER"))
                            .header("Idempotency-Key", k1.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"method\":\"QRIS\",\"amount\":10000000}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("PAYMENT_IN_PROGRESS"))
                    .andExpect(header().string("Retry-After", "3"));
        } finally {
            ConcurrencyXenditStub.releaseCreateHold();
            executor.shutdownNow();
        }
    }

    // =====================================================================
    // webhook helper (full correlation payload, real endpoint)
    // =====================================================================

    private org.springframework.test.web.servlet.ResultActions webhookCapture(
            String requestId, String reference, long amount, String paymentId) throws Exception {
        String body = "{\"event\":\"payment.capture\",\"data\":{\"payment_request_id\":\"" + requestId
                + "\",\"reference_id\":\"" + reference + "\",\"payment_id\":\"" + paymentId
                + "\",\"request_amount\":" + amount + ",\"currency\":\"IDR\",\"country\":\"ID\""
                + ",\"channel_code\":\"QRIS\",\"type\":\"PAY\",\"status\":\"SUCCEEDED\"}}";
        return mvc.perform(post(WEBHOOK)
                .header("X-Callback-Token", CALLBACK_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // =====================================================================
    // fixtures
    // =====================================================================

    private long createUser(String role, String suffix) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', ?, ?, true, 0, ?, ?) RETURNING id
                """, Long.class, fixture.toLowerCase() + suffix + "@test.local", fixture + suffix, role, now, now);
    }

    private long createOrder(long subtotal, long totalDue) {
        orderCounter++;
        Timestamp now = Timestamp.from(Instant.now());
        LocalDate businessDate = businessDates.currentBusinessDate(VENUE_ZONE, CUTOFF);
        return jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, ?::date, 'OPEN', ?, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, venueId, fixture + "-O" + orderCounter, cashierId,
                java.sql.Date.valueOf(businessDate), subtotal, totalDue, now, now, now);
    }

    private void seedOrderItem(long orderId, long productId, long lineTotal) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO order_items (order_id,product_id,item_type,product_name_snapshot,
                                         unit_price_snapshot,quantity,line_total,status,created_at,updated_at)
                VALUES (?, ?, 'PRODUCT', ?, ?, 1, ?, 'ORDERED', ?, ?)
                """, orderId, productId, fixture + "-seed", lineTotal, lineTotal, now, now);
    }

    private void seedShift() {
        LocalDate businessDate = businessDates.currentBusinessDate(VENUE_ZONE, CUTOFF);
        shiftRepository.insertOpenShift(venueId, cashierId, businessDate, 0L, Instant.now());
    }

    private long seedTable() {
        return jdbc.queryForObject("""
                INSERT INTO venue_tables (venue_id,code,capacity,status,is_active,version)
                VALUES (?, ?, 4, 'AVAILABLE', true, 0) RETURNING id
                """, Long.class, venueId, fixture + "-T1");
    }

    private long seedProductWithRecipe() {
        Timestamp now = Timestamp.from(Instant.now());
        long ingredientId = jdbc.queryForObject(
                "INSERT INTO ingredients(sku,name,unit,is_active) VALUES (?, ?, 'ML', true) RETURNING id",
                Long.class, fixture, fixture);
        long productId = jdbc.queryForObject("""
                INSERT INTO products (venue_id,sku,name,price,is_bottle_keepable,is_active,version,created_at,updated_at)
                VALUES (?, ?, ?, 1000000, false, true, 0, ?, ?) RETURNING id
                """, Long.class, venueId, fixture, fixture, now, now);
        long locationId = jdbc.queryForObject(
                "INSERT INTO stock_locations(venue_id,name,type) VALUES (?, ?, 'BAR') RETURNING id",
                Long.class, venueId, fixture);
        jdbc.update("""
                INSERT INTO inventory_items (location_id,ingredient_id,quantity,reorder_level,version,updated_at)
                VALUES (?, ?, 100000, 0, 0, ?)
                """, locationId, ingredientId, now);
        long recipeId = jdbc.queryForObject(
                "INSERT INTO recipes(product_id,version,updated_at) VALUES (?, 1, ?) RETURNING id",
                Long.class, productId, now);
        jdbc.update("INSERT INTO recipe_items(recipe_id,ingredient_id,quantity) VALUES (?, ?, 5)", recipeId, ingredientId);
        return productId;
    }

    /** V9-valid Xendit QRIS payment: ACCEPTED + PENDING, real nv_pay_<id> reference, unique request id. */
    private long seedQrisPayment(long orderId, long amount, String requestId) {
        Timestamp now = Timestamp.from(Instant.now());
        long paymentId = jdbc.queryForObject("""
                INSERT INTO payments (order_id,venue_id,business_date,method,amount,status,provider,
                                      provider_reference_id,provider_request_id,charge_state,
                                      charge_started_at,charge_attempts,created_by,created_at,updated_at,version)
                VALUES (?, ?, CURRENT_DATE, 'QRIS', ?, 'PENDING', 'XENDIT',
                        'seed_pending_' || ?::text, ?, 'ACCEPTED', ?, 1, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, orderId, venueId, amount, UUID.randomUUID().toString(), requestId,
                now, cashierId, now, now);
        String reference = "nv_pay_" + paymentId;
        jdbc.update("UPDATE payments SET provider_reference_id = ? WHERE id = ?", reference, paymentId);
        return paymentId;
    }

    /** PENDING + ACCEPTED + request id + charge_started_at older than minAge (reconciliation candidate). */
    private long seedReconciliationCandidate(long orderId, long amount, String requestId) {
        Timestamp started = Timestamp.from(clock.instant().minus(Duration.ofHours(2)));
        Timestamp created = Timestamp.from(clock.instant());
        long paymentId = jdbc.queryForObject("""
                INSERT INTO payments (order_id,venue_id,business_date,method,amount,status,provider,
                                      provider_reference_id,provider_request_id,charge_state,
                                      charge_started_at,charge_attempts,created_by,created_at,updated_at,version)
                VALUES (?, ?, ?::date, 'QRIS', ?, 'PENDING', 'XENDIT',
                        'seed_pending_' || ?::text, ?, 'ACCEPTED', ?, 1, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, orderId, venueId, java.sql.Date.valueOf("2026-01-01"), amount,
                UUID.randomUUID().toString(), requestId, started, cashierId, created, created);
        String reference = "nv_pay_" + paymentId;
        jdbc.update("UPDATE payments SET provider_reference_id = ? WHERE id = ?", reference, paymentId);
        return paymentId;
    }

    // =====================================================================
    // assertions helpers
    // =====================================================================

    private UUID key() {
        UUID id = UUID.randomUUID();
        keys.add(id);
        return id;
    }

    private String bearer(long id, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(id))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String jsonDetail(MvcResult result) {
        try {
            var node = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            return node.path("detail").asText("");
        } catch (Exception e) {
            return "<unreadable detail: " + e.getClass().getSimpleName() + ">";
        }
    }

    private String jsonField(String json, String field) {
        try {
            return mapper.readTree(json).path(field).asText(null);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + field + " from: " + json, e);
        }
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private long paidTotal(long orderId) {
        return jdbc.queryForObject("SELECT paid_total FROM orders WHERE id = ?", Long.class, orderId);
    }

    private long totalDue(long orderId) {
        return jdbc.queryForObject("SELECT total_due FROM orders WHERE id = ?", Long.class, orderId);
    }

    private long paymentCount(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Long.class, orderId);
    }

    private String paymentStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId);
    }

    private String paymentStatus(long orderId, String method) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ? AND method = ?",
                String.class, orderId, method);
    }

    private String paymentStatusById(long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String chargeStateById(long paymentId) {
        return jdbc.queryForObject("SELECT charge_state FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String referenceOf(long paymentId) {
        return jdbc.queryForObject("SELECT provider_reference_id FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String tableStatus(long tableId) {
        return jdbc.queryForObject("SELECT status FROM venue_tables WHERE id = ?", String.class, tableId);
    }

    private BigDecimal stockQuantity() {
        // the T2 fixture creates exactly one inventory item for this venue
        return jdbc.queryForObject("""
                SELECT ii.quantity FROM inventory_items ii
                JOIN stock_locations sl ON sl.id = ii.location_id
                WHERE sl.venue_id = ?
                """, BigDecimal.class, venueId);
    }

    private long eventCount(long paymentId, String result) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_events WHERE payment_id = ? AND result = ?",
                Long.class, paymentId, result);
    }

    private long totalEventCount(long paymentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id = ?",
                Long.class, paymentId);
    }

    private String eventKey(long paymentId, String result) {
        return jdbc.queryForObject("""
                SELECT event_key FROM payment_events WHERE payment_id = ? AND result = ?
                """, String.class, paymentId, result);
    }

    private long auditCount(long paymentId, String action) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'PAYMENT' AND entity_id = ? AND action = ?
                """, Long.class, paymentId, action);
    }

    private long anomalyCount(long orderId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'PAYMENT' AND entity_id IN (SELECT id FROM payments WHERE order_id = ?)
                  AND action LIKE 'PAYMENT_%'
                  AND action NOT IN ('PAYMENT_CREATED', 'PAYMENT_SETTLED', 'PAYMENT_STATUS_CHANGED')
                """, Long.class, orderId);
    }

    /**
     * Bounded poll of pg_stat_activity: counts sessions currently waiting on a heavyweight
     * lock. While the holder connection owns the order row, the only lock-waiting sessions
     * in this test are the two application workers.
     * Observation only — never used to order transactions.
     */
    private boolean waitForBlockedSessions(long holderPid, int expected, long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity sa
                    WHERE sa.datname = current_database()
                      AND sa.state = 'active'
                      AND sa.wait_event_type = 'Lock'
                    """, Integer.class);
            if (blocked != null && blocked >= expected) {
                return true;
            }
            LockSupport.parkNanos(50_000_000L);
        }
        return false;
    }

    private List<String> sessionDiagnostics() {
        return jdbc.query("""
                SELECT pid, state, wait_event_type, pg_blocking_pids(pid)::text, left(query, 100)
                FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                """, (rs, n) -> rs.getInt(1) + "/" + rs.getString(2) + "/" + rs.getString(3)
                + "/blockers=" + rs.getString(4) + "/" + rs.getString(5));
    }

    private static void awaitQuietly(Future<?> future) {
        if (future == null) {
            return;
        }
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // already joined, failed or cancelled — cleanup must not depend on the outcome
        }
    }

    // =====================================================================
    // test-local Xendit stub (no modifications to existing XenditStub)
    // =====================================================================

    /**
     * JDK HttpServer stub owned by this class. Create POST can be held by a latch
     * (T5); the first Status GET can be held arrival-order (T4) while every later
     * GET answers immediately. All waits are bounded via latches awaited by callers.
     */
    static final class ConcurrencyXenditStub {
        static final String QR = "00020101021228-M5A-TEST-QR-NOT-REAL";
        static final String PR_ID = "pr-m5a-conv-0001";
        private static final com.sun.net.httpserver.HttpServer SERVER;
        static final AtomicInteger createCount = new AtomicInteger();
        static final AtomicInteger statusCount = new AtomicInteger();
        static volatile String lastCreateBody;
        private static volatile CountDownLatch createHold = new CountDownLatch(0);
        static volatile CountDownLatch createArrived = new CountDownLatch(1);
        private static volatile CountDownLatch statusFirstHold = new CountDownLatch(0);
        private static volatile AtomicInteger statusHoldTaken; // null = gate not armed
        static volatile CountDownLatch statusFirstArrived = new CountDownLatch(1);
        private static volatile String statusReferenceId = "";
        private static volatile long statusAmount;
        private static volatile String statusPaymentId = "pay-m5a";

        static {
            try {
                SERVER = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            SERVER.createContext("/v3/payment_requests", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    createCount.incrementAndGet();
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    lastCreateBody = new String(body, StandardCharsets.UTF_8);
                    createArrived.countDown();
                    CountDownLatch hold = createHold;
                    if (hold.getCount() > 0) {
                        try { hold.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    respond(exchange, "{\"id\":\"" + PR_ID + "\",\"status\":\"PENDING\",\"actions\":"
                            + "[{\"action\":\"QR_STRING\",\"value\":\"" + QR + "\"}]}");
                    return;
                }
                // Status GET: CAS gate — ONLY the first arrival waits, later GETs answer immediately
                statusCount.incrementAndGet();
                String[] parts = exchange.getRequestURI().getPath().split("/");
                String requestedId = parts[parts.length - 1];
                AtomicInteger gate = statusHoldTaken;
                boolean firstToArrive = gate != null && gate.compareAndSet(0, 1);
                statusFirstArrived.countDown();
                if (firstToArrive) {
                    CountDownLatch hold = statusFirstHold;
                    try { hold.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                respond(exchange, "{\"id\":\"" + requestedId + "\",\"reference_id\":\"" + statusReferenceId
                        + "\",\"status\":\"SUCCEEDED\",\"request_amount\":" + statusAmount
                        + ",\"currency\":\"IDR\",\"country\":\"ID\",\"channel_code\":\"QRIS\""
                        + ",\"type\":\"PAY\",\"payment_id\":\"" + statusPaymentId + "\"}");
            });
            SERVER.setExecutor(Executors.newCachedThreadPool());
            SERVER.start();
        }

        static int port() {
            return SERVER.getAddress().getPort();
        }

        static void reset() {
            createCount.set(0);
            statusCount.set(0);
            lastCreateBody = null;
            createHold = new CountDownLatch(0);
            createArrived = new CountDownLatch(1);
            statusFirstHold = new CountDownLatch(0);
            statusHoldTaken = null;
            statusFirstArrived = new CountDownLatch(1);
            statusReferenceId = "";
            statusAmount = 0;
            statusPaymentId = "pay-m5a";
        }

        static void armCreateHold() {
            createHold = new CountDownLatch(1);
            createArrived = new CountDownLatch(1);
        }

        static void releaseCreateHold() {
            createHold.countDown();
        }

        /** Arm the arrival-order Status gate used by T4. */
        static void armStatusGate(String referenceId, long amount) {
            statusReferenceId = referenceId;
            statusAmount = amount;
            statusHoldTaken = new AtomicInteger(0);
            statusFirstHold = new CountDownLatch(1);
            statusFirstArrived = new CountDownLatch(1);
        }

        static void releaseStatusGate() {
            statusFirstHold.countDown();
        }

        /** Set Status-API correlation context for immediate (non-gated) GETs. */
        static void setStatusContext(String referenceId, long amount) {
            statusReferenceId = referenceId;
            statusAmount = amount;
        }

        /** Provider-side payment id echoed by the Status API (drives event_key for reconcile rows). */
        static void setStatusPaymentId(String paymentId) {
            statusPaymentId = paymentId;
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) {
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (Exception ignored) {
                // client already gone
            } finally {
                exchange.close();
            }
        }
    }

    // =====================================================================
    // injected fixed Clock for reconciliation minAge
    // =====================================================================

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-01T02:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void set(Instant instant) {
            this.current = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
