package com.nightvenue.api.payment;

import com.nightvenue.api.TestcontainersConfig;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.oauth2.jwt.JwsHeader.with;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3-B QRIS create integration tests. NOT @Transactional on purpose: TX1/TX2
 * boundaries must commit for real. Every test cleans its own rows.
 */
@SpringBootTest(properties = {
        "nightvenue.seed.enabled=false",
        "nightvenue.payment.xendit.secret-key=" + XenditStub.SECRET,
        "nightvenue.payment.xendit.read-timeout-ms=" + XenditStub.READ_TIMEOUT_MS
})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class PaymentCreateXenditIntegrationTests {
    private static final String API = "/api/v1/orders";
    private static final String QRIS_BODY = "{\"method\":\"QRIS\",\"amount\":%d}";
    private static final List<UUID> KEYS = new ArrayList<>();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    private final ObjectMapper mapper = new ObjectMapper();

    private String fixture;
    private long venueId, venueBId, cashierId, orderId, orderIdB;
    private final List<UUID> keys = new ArrayList<>();

    @DynamicPropertySource
    static void xenditBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("nightvenue.payment.xendit.base-url", () -> "http://localhost:" + XenditStub.port());
    }

    @BeforeEach
    void setup() {
        fixture = "M3B-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        cashierId = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', 'QRIS Test', 'CASHIER', true, 0, ?, ?) RETURNING id
                """, Long.class, fixture.toLowerCase() + "@test.local", now, now);
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture, fixture, now, now);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueId);
        venueBId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, ?, ?) RETURNING id
                """, Long.class, fixture + "-B", fixture + "-B", now, now);
        orderId = insertOrder(venueId, fixture, 10000);
        orderIdB = insertOrder(venueBId, fixture + "-B", 10000);
    }

    @AfterEach
    void cleanup() {
        if (fixture == null) return;
        keys.forEach(key -> jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key));
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN (?, ?) OR actor_user_id = ?", venueId, venueBId, cashierId);
        jdbc.update("DELETE FROM payments WHERE order_id IN (?, ?)", orderId, orderIdB);
        jdbc.update("DELETE FROM orders WHERE id IN (?, ?)", orderId, orderIdB);
        jdbc.update("DELETE FROM user_venues WHERE user_id = ?", cashierId);
        jdbc.update("DELETE FROM users WHERE id = ?", cashierId);
        jdbc.update("DELETE FROM venues WHERE id IN (?, ?)", venueId, venueBId);
    }

    private long insertOrder(long venue, String orderNumber, long totalDue) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', ?, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, venue, orderNumber, cashierId, totalDue, totalDue, now, now, now);
    }

    private String bearer() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(cashierId))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", "CASHIER").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private UUID key() {
        UUID key = UUID.randomUUID();
        keys.add(key);
        return key;
    }

    private ResultActions createQris(UUID key, long amount) throws Exception {
        return mvc.perform(post(API + "/" + orderId + "/payments")
                .header("Authorization", bearer()).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(QRIS_BODY.formatted(amount)));
    }

    private Map<String, Object> paymentRow(long paymentId) {
        return jdbc.queryForMap("SELECT method, amount, status, provider, provider_order_id, " +
                "provider_transaction_id, provider_idempotency_key, provider_reference_id, provider_request_id, " +
                "qr_payload, charge_state, charge_attempts, version FROM payments WHERE id = ?", paymentId);
    }

    private boolean orderHasActivePayment(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM payments WHERE order_id = ? AND status IN ('PENDING','PAID'))",
                Boolean.class, id));
    }

    // ---------- 6.1 + 6.2: contract + happy path ----------

    @Test
    void happyPath_201_confirmedQr_neverPaid_providerColumnsCorrect() throws Exception {
        XenditStub.reset(XenditStub.Mode.SUCCESS, orderId, null, jdbc);
        UUID key = key();
        MvcResult result = createQris(key, 10000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("QRIS"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.qrString").value(XenditStub.QR))
                .andReturn();

        // 6.1 provider contract: Basic auth, api-version, flat body, no extras
        assertEquals(1, XenditStub.count());
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((XenditStub.SECRET + ":").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expectedAuth, XenditStub.lastAuth);
        assertEquals("2024-11-11", XenditStub.lastApiVersion);
        Map<String, Object> sent = mapper.readValue(XenditStub.lastBody, Map.class);
        assertEquals(Map.of("reference_id", "nv_pay_" + jsonLong(result, "$.id"),
                "type", "PAY", "country", "ID", "currency", "IDR", "request_amount", 10000,
                "channel_code", "QRIS"), sent);

        // 6.2 persistence
        long paymentId = jsonLong(result, "$.id");
        Map<String, Object> row = paymentRow(paymentId);
        assertEquals("QRIS", row.get("method"));
        assertEquals("PENDING", row.get("status")); // never PAID on create
        assertEquals("ACCEPTED", row.get("charge_state"));
        assertEquals("XENDIT", row.get("provider"));
        assertEquals("nv_pay_" + paymentId, row.get("provider_reference_id"));
        assertEquals(XenditStub.REQUEST_ID, row.get("provider_request_id"));
        assertEquals(XenditStub.QR, row.get("qr_payload"));
        assertNullColumn(row, "provider_order_id");
        assertNullColumn(row, "provider_idempotency_key");
        assertNullColumn(row, "provider_transaction_id");
        assertEquals(1, ((Number) row.get("charge_attempts")).intValue());
        assertTrue(orderHasActivePayment(orderId)); // PENDING reserves the balance
    }

    // ---------- 6.3: idempotent replay ----------

    @Test
    void replay_sameKeySamePayload_snapshot_noSecondProviderCall_differentPayload_422() throws Exception {
        XenditStub.reset(XenditStub.Mode.SUCCESS, orderId, null, jdbc);
        UUID key = key();
        MvcResult first = createQris(key, 10000).andExpect(status().isCreated()).andReturn();

        MvcResult replay = createQris(key, 10000).andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, XenditStub.count());

        createQris(key, 5000) // same key, different payload
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key reused with a different payload"));
        assertEquals(1, XenditStub.count());
    }

    // ---------- 6.4: in-flight replay ----------

    @Test
    void inFlightReplay_409_paymentInProgress_retryAfter3() throws Exception {
        XenditStub.reset(XenditStub.Mode.BLOCKED, orderId, null, jdbc);
        UUID key = key();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> first = pool.submit(() -> createQris(key, 10000).andReturn());
            awaitStubRequest();
            createQris(key, 10000)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("PAYMENT_IN_PROGRESS"))
                    .andExpect(header().string("Retry-After", "3"));
            XenditStub.releaseBlock();
            assertEquals(201, first.get(10, TimeUnit.SECONDS).getResponse().getStatus());
        }
    }

    // ---------- 6.5: provider validation rejection releases the reservation ----------

    @Test
    void providerValidation400_rejected_failed_reservationReleased_voidAllowedAfterwards() throws Exception {
        XenditStub.reset(XenditStub.Mode.VALIDATION, orderId, null, jdbc);
        UUID key = key();
        createQris(key, 10000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.method").value("QRIS"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.qrString").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT charge_state, status, provider_request_id, qr_payload FROM payments WHERE order_id = ?", orderId);
        assertEquals("REJECTED", row.get("charge_state"));
        assertEquals("FAILED", row.get("status"));
        assertNullColumn(row, "provider_request_id");
        assertNullColumn(row, "qr_payload");
        assertFalse(orderHasActivePayment(orderId)); // reservation released
        assertEquals(422, (int) jdbc.queryForObject(
                "SELECT response_status FROM idempotency_keys WHERE key = ?", Integer.class, key));

        // the order can now be voided
        mvc.perform(post(API + "/" + orderId + "/void")
                        .header("Authorization", bearer()).header("Idempotency-Key", key().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                .andExpect(status().isOk());
    }

    // ---------- 6.6: ambiguous outcomes ----------

    @Test
    void ambiguous_2xxWithIdNoQr_idPersisted_reservationHeld_voidBlocked() throws Exception {
        XenditStub.reset(XenditStub.Mode.NO_QR, orderId, null, jdbc);
        UUID key = key();
        createQris(key, 10000)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.qrString").doesNotExist());
        Map<String, Object> row = paymentRowForOrder();
        assertEquals("AMBIGUOUS", row.get("charge_state"));
        assertEquals(XenditStub.REQUEST_ID, row.get("provider_request_id")); // id kept (5.7)
        assertNullColumn(row, "qr_payload");
        assertHeldAndBlocked(key);
    }

    @Test
    void ambiguous_2xxWithoutId_noIdPersisted() throws Exception {
        XenditStub.reset(XenditStub.Mode.NO_ID, orderId, null, jdbc);
        createQris(key(), 10000).andExpect(status().isAccepted());
        assertNullColumn(paymentRowForOrder(), "provider_request_id");
        assertEquals(1, XenditStub.count()); // no automatic retry
    }

    @Test
    void ambiguous_overLongId_notPersisted_tx2DoesNotFail() throws Exception {
        XenditStub.reset(XenditStub.Mode.LONG_ID, orderId, null, jdbc);
        createQris(key(), 10000)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertNullColumn(paymentRowForOrder(), "provider_request_id");
        assertEquals("AMBIGUOUS", paymentRowForOrder().get("charge_state"));
        assertTrue(orderHasActivePayment(orderId));
    }

    @Test
    void ambiguous_5xx_202_reservationHeld_voidBlocked() throws Exception {
        XenditStub.reset(XenditStub.Mode.SERVER_500, orderId, null, jdbc);
        UUID key = key();
        createQris(key, 10000).andExpect(status().isAccepted());
        assertEquals("AMBIGUOUS", paymentRowForOrder().get("charge_state"));
        assertEquals(202, (int) jdbc.queryForObject(
                "SELECT response_status FROM idempotency_keys WHERE key = ?", Integer.class, key));
        assertHeldAndBlocked(key);
    }

    @Test
    void ambiguous_timeout_202_exactlyOneProviderCall() throws Exception {
        XenditStub.reset(XenditStub.Mode.TIMEOUT, orderId, null, jdbc);
        createQris(key(), 10000).andExpect(status().isAccepted());
        assertEquals(1, XenditStub.count()); // no retry after read timeout
        assertEquals("AMBIGUOUS", paymentRowForOrder().get("charge_state"));
        assertTrue(orderHasActivePayment(orderId));
    }

    // ---------- 6.7 blank secret lives in PaymentCreateXenditNotConfiguredTests ----------

    // ---------- 6.8: auth, scoping, balance ----------

    @Test
    void crossVenuePayment_404() throws Exception {
        XenditStub.reset(XenditStub.Mode.SUCCESS, orderId, null, jdbc);
        UUID key = key();
        mvc.perform(post(API + "/" + orderIdB + "/payments")
                        .header("Authorization", bearer()).header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(QRIS_BODY.formatted(10000)))
                .andExpect(status().isNotFound());
        assertEquals(0, XenditStub.count());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderIdB));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key));
    }

    @Test
    void amountOverRemaining_422() throws Exception {
        XenditStub.reset(XenditStub.Mode.SUCCESS, orderId, null, jdbc);
        createQris(key(), 10001).andExpect(status().isUnprocessableEntity());
        assertEquals(0, XenditStub.count());
    }

    @Test
    void parallelQris_exceedingTotalDue_exactlyOneConfirmed() throws Exception {
        XenditStub.reset(XenditStub.Mode.SUCCESS, orderId, null, jdbc);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = pool.invokeAll(List.of(
                    (Callable<Integer>) () -> createQris(key(), 8000).andReturn().getResponse().getStatus(),
                    (Callable<Integer>) () -> createQris(key(), 8000).andReturn().getResponse().getStatus()));
            int created = 0, rejected = 0;
            for (Future<Integer> f : results) {
                int s = f.get(10, TimeUnit.SECONDS);
                if (s == 201) created++;
                if (s == 422) rejected++;
            }
            assertEquals(1, created);
            assertEquals(1, rejected);
        }
        assertEquals(1, XenditStub.count());
    }

    // ---------- 6.9: no locks / open transaction during the provider call ----------

    @Test
    void providerCallHoldsNoRowLock_tx1CommittedBeforeHttp() throws Exception {
        XenditStub.reset(XenditStub.Mode.BLOCKED, orderId, null, jdbc);
        UUID key = key();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> first = pool.submit(() -> createQris(key, 10000).andReturn());
            awaitStubRequest();
            // (a) the order row is NOT locked while we wait for the provider
            jdbc.execute("SELECT id FROM orders WHERE id = " + orderId + " FOR UPDATE NOWAIT");
            // (b) TX1 already committed: the SENDING payment row is visible to another connection
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT charge_state, status, charge_attempts FROM payments WHERE order_id = ?", orderId);
            assertEquals("SENDING", row.get("charge_state"));
            assertEquals("PENDING", row.get("status"));
            assertEquals(1, ((Number) row.get("charge_attempts")).intValue());
            XenditStub.releaseBlock();
            assertEquals(201, first.get(10, TimeUnit.SECONDS).getResponse().getStatus());
        }
    }

    // ---------- 6.10: TX2 fencing ----------

    @Test
    void fencedPayment_409_stateConflict_idempotencyNotCompleted_noOverwrite() throws Exception {
        XenditStub.reset(XenditStub.Mode.FENCE, orderId, null, jdbc);
        UUID key = key();
        createQris(key, 10000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("PAYMENT_STATE_CONFLICT"));

        Integer snapshotStatus = jdbc.queryForObject(
                "SELECT response_status FROM idempotency_keys WHERE key = ?", Integer.class, key);
        assertEquals(null, snapshotStatus); // NOT completed
        Map<String, Object> row = paymentRowForOrder();
        assertEquals("ACCEPTED", row.get("charge_state")); // the external mutation stands
        assertNullColumn(row, "qr_payload");               // TX2 did not overwrite
        assertNotEquals("PAID", row.get("status"));
    }

    // ---------- 6.11: atomicity of TX2 ----------

    @Test
    void tx2Failure_rollsBackPayment_rowStaysSending_noSnapshot() throws Exception {
        UUID key = key();
        XenditStub.reset(XenditStub.Mode.FAIL_TX2, orderId, key.toString(), jdbc);
        // complete() fails inside TX2 (the snapshot row was removed): the whole TX2 must roll back.
        // In production this surfaces as HTTP 500; MockMvc rethrows the unhandled exception.
        jakarta.servlet.ServletException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                jakarta.servlet.ServletException.class, () -> createQris(key, 10000).andReturn());
        assertTrue(thrown.getMessage().contains("Failed to store idempotency snapshot"),
                "unexpected failure: " + thrown.getMessage());

        Map<String, Object> row = paymentRowForOrder();
        assertEquals("SENDING", row.get("charge_state")); // no partial state
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, ((Number) row.get("version")).intValue());
        assertNullColumn(row, "provider_request_id");
        assertNullColumn(row, "qr_payload");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE key = ? AND response_status IS NOT NULL",
                Integer.class, key));
    }

    // ---------- 6.12: log hygiene ----------

    @Test
    void logs_neverContainSecretAuthHeaderOrQr() throws Exception {
        Logger xenditLogger = (Logger) LoggerFactory.getLogger(com.nightvenue.api.payment.xendit.XenditClient.class);
        Logger txLogger = (Logger) LoggerFactory.getLogger(QrisCreateTransactions.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        xenditLogger.addAppender(appender);
        txLogger.addAppender(appender);
        try {
            XenditStub.reset(XenditStub.Mode.SUCCESS, orderId, null, jdbc);
            UUID key = key();
            createQris(key, 10000).andExpect(status().isCreated());

            XenditStub.reset(XenditStub.Mode.VALIDATION, orderId, null, jdbc);
            createQris(key(), 10000).andExpect(status().isUnprocessableEntity());
        } finally {
            xenditLogger.detachAppender(appender);
            txLogger.detachAppender(appender);
        }
        List<ILoggingEvent> events = appender.list;
        assertFalse(events.isEmpty());
        for (ILoggingEvent event : events) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains(XenditStub.SECRET), "log leaks secret");
            assertFalse(line.contains("Basic "), "log leaks Authorization");
            assertFalse(line.contains(XenditStub.QR), "log leaks QR payload");
        }
    }

    // ---------- 6.13: QRIS_MIDTRANS stays rejected ----------

    @Test
    void qrisMidtransCreate_stillRejected422() throws Exception {
        XenditStub.reset(XenditStub.Mode.SUCCESS, orderId, null, jdbc);
        UUID key = key();
        mvc.perform(post(API + "/" + orderId + "/payments")
                        .header("Authorization", bearer()).header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"QRIS_MIDTRANS\",\"amount\":1000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("PAYMENT_METHOD_NOT_SUPPORTED"));
        assertEquals(0, XenditStub.count());
    }

    // ---------- helpers ----------

    private void assertHeldAndBlocked(UUID key) throws Exception {
        assertTrue(orderHasActivePayment(orderId)); // AMBIGUOUS reservation is held
        assertEquals(202, (int) jdbc.queryForObject(
                "SELECT response_status FROM idempotency_keys WHERE key = ?", Integer.class, key));
        // BR-20: an order with an active payment cannot be voided
        mvc.perform(post(API + "/" + orderId + "/void")
                        .header("Authorization", bearer()).header("Idempotency-Key", key().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order has active payments"));
    }

    private Map<String, Object> paymentRowForOrder() {
        return jdbc.queryForMap("""
                SELECT method, amount, status, provider, provider_order_id, provider_transaction_id,
                       provider_idempotency_key, provider_reference_id, provider_request_id, qr_payload,
                       charge_state, charge_attempts, version
                FROM payments WHERE order_id = ?
                """, orderId);
    }

    private static void assertNullColumn(Map<String, Object> row, String column) {
        assert row.containsKey(column) : "missing column " + column;
        assertEquals(null, row.get(column), column + " must be NULL but was " + row.get(column));
    }

    private long jsonLong(MvcResult result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString()).read(path, Long.class);
    }

    /** waits (bounded) until the stub has received the provider request */
    private static void awaitStubRequest() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (XenditStub.count() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(XenditStub.count() > 0, "provider stub never received the create request");
    }
}
