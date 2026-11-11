package com.nightvenue.api.payment.reconciliation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.payment.XenditStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4-B reconciliation integration tests. Not @Transactional: every scenario drives
 * real transactions/commits through the reconciler and the webhook endpoint.
 * The scheduler is disabled; tests call {@link PaymentReconciliationService#reconcile()}
 * directly with an injected mutable Clock.
 */
@SpringBootTest(properties = {
        "nightvenue.seed.enabled=false",
        "nightvenue.payment.xendit.secret-key=test-secret-not-real",
        "nightvenue.payment.xendit.read-timeout-ms=5000",
        "nightvenue.payment.reconciliation.enabled=false",
        "nightvenue.payment.reconciliation.min-age=PT1H",
        "nightvenue.payment.reconciliation.batch-size=50",
        "nightvenue.payment.reconciliation.max-per-cycle=50",
        "nightvenue.payment.reconciliation.fixed-delay=PT1H"
})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, PaymentReconciliationIntegrationTests.ClockConfig.class})
class PaymentReconciliationIntegrationTests {

    private static final String VENUE_ZONE = "Asia/Jakarta";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentReconciliationService reconciler;
    @Autowired PaymentReconciliationProperties properties;
    @Autowired ApplicationContext context;
    @Autowired MutableClock clock;

    private long venueId, userId, orderId;
    private String fixture;
    private int counter;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("nightvenue.payment.xendit.base-url", () -> "http://localhost:" + XenditStub.port());
    }

    @BeforeEach
    void setup() {
        clock.set(Instant.parse("2026-01-01T02:00:00Z"));
        fixture = "REC-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(clock.instant());
        venueId = jdbc.queryForObject("""
                INSERT INTO venues(code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES(?,?,'Asia/Jakarta',6,true,?,?) RETURNING id
                """, Long.class, fixture, fixture, now, now);
        userId = jdbc.queryForObject("""
                INSERT INTO users(email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES(?,'hash','Reconciler','CASHIER',true,0,?,?) RETURNING id
                """, Long.class, fixture.toLowerCase() + "@test.local", now, now);
        orderId = jdbc.queryForObject("""
                INSERT INTO orders(venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                   opened_at,created_at,updated_at,version)
                VALUES(?,?,?,CURRENT_DATE,'OPEN',10000,10000,?,?,?,0) RETURNING id
                """, Long.class, venueId, fixture, userId, now, now, now);
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS, orderId, null, jdbc);
        counter = 0;
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_payment_event_insert ON payment_events");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_payment_event_insert()");
        jdbc.update("DELETE FROM payment_events WHERE payment_id IN (SELECT id FROM payments WHERE venue_id=?)", venueId);
        jdbc.update("DELETE FROM payments WHERE venue_id=?", venueId);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id=?", venueId);
        jdbc.update("DELETE FROM orders WHERE venue_id=?", venueId);
        jdbc.update("DELETE FROM users WHERE id=?", userId);
        jdbc.update("DELETE FROM venues WHERE id=?", venueId);
    }

    // ---------- provider outcomes ----------

    @Test
    void ambiguousSucceededSettlesPaidWithChargeStateUnchangedAndOneEvent() {
        Seeded seeded = seedEligible("AMBIGUOUS", "SUCCEEDED");
        assertEquals(1, reconciler.reconcile());

        assertEquals("PAID", paymentStatus(seeded.id()));
        assertEquals("AMBIGUOUS", chargeState(seeded.id()));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(1, eventCount(seeded.id()));
        assertEquals("PROCESSED", jdbc.queryForObject(
                "SELECT result FROM payment_events WHERE payment_id=?", String.class, seeded.id()));
        assertTrue(jdbc.queryForObject("SELECT event_key FROM payment_events WHERE payment_id=?",
                String.class, seeded.id()).startsWith("reconcile:SUCCEEDED:"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action='PAYMENT_SETTLED' AND entity_id=?
                """, Integer.class, seeded.id()));
    }

    @Test
    void acceptedSucceededSettlesPaid() {
        Seeded seeded = seedEligible("ACCEPTED", "SUCCEEDED");
        reconciler.reconcile();
        assertEquals("PAID", paymentStatus(seeded.id()));
        assertEquals("ACCEPTED", chargeState(seeded.id()));
        assertEquals(1, eventCount(seeded.id()));
    }

    @Test
    void failedExpiredAndCanceledMapping() {
        Seeded failed = seedEligible("ACCEPTED", "FAILED");
        Seeded expired = seedEligible("AMBIGUOUS", "EXPIRED");
        Seeded canceled = seedEligible("ACCEPTED", "CANCELED");

        assertEquals(3, reconciler.reconcile());

        assertEquals("FAILED", paymentStatus(failed.id()));
        assertEquals("ACCEPTED", chargeState(failed.id()));
        assertEquals("EXPIRED", paymentStatus(expired.id()));
        assertEquals("AMBIGUOUS", chargeState(expired.id()));
        // PM decision (a): PaymentStateMachine blocks PENDING -> CANCELLED, so the row is untouched.
        assertEquals("PENDING", paymentStatus(canceled.id()));
        assertEquals(0, eventCount(canceled.id()));
    }

    @Test
    void nonTerminalProviderStatusLeavesRowUnchanged() {
        Seeded requiresAction = seedEligible("ACCEPTED", "REQUIRES_ACTION");
        Seeded authorized = seedEligible("ACCEPTED", "AUTHORIZED");
        Seeded accepting = seedEligible("AMBIGUOUS", "ACCEPTING_PAYMENTS");
        Seeded unknown = seedEligible("AMBIGUOUS", "SOMETHING_NEW");

        reconciler.reconcile();

        for (Seeded seeded : List.of(requiresAction, authorized, accepting, unknown)) {
            assertEquals("PENDING", paymentStatus(seeded.id()));
            assertEquals(0, eventCount(seeded.id()));
        }
    }

    // ---------- Status API failures and retries ----------

    @Test
    void statusApiFailuresNeverWriteAndNextCycleRetries() {
        Seeded notFound = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.mapHttpError(notFound.requestId(), 404);
        Seeded serverError = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.mapHttpError(serverError.requestId(), 500);
        Seeded unauthorized = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.mapHttpError(unauthorized.requestId(), 401);
        Seeded forbidden = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.mapHttpError(forbidden.requestId(), 403);

        reconciler.reconcile();

        for (Seeded seeded : List.of(notFound, serverError, unauthorized, forbidden)) {
            assertEquals("PENDING", paymentStatus(seeded.id()));
            assertEquals(0, eventCount(seeded.id()));
        }

        // malformed body
        Seeded malformed = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.setMode(XenditStub.Mode.STATUS_MALFORMED);
        reconciler.reconcile();
        assertEquals("PENDING", paymentStatus(malformed.id()));
        assertEquals(0, eventCount(malformed.id()));

        // timeout (stub sleeps 6s, client read timeout 5s)
        Seeded timeout = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.setMode(XenditStub.Mode.STATUS_TIMEOUT_LONG);
        reconciler.reconcile();
        assertEquals("PENDING", paymentStatus(timeout.id()));
        assertEquals(0, eventCount(timeout.id()));

        // next cycle with a healthy provider resolves every row that was left eligible
        XenditStub.setMode(XenditStub.Mode.STATUS_SUCCESS);
        for (Seeded seeded : List.of(notFound, serverError, unauthorized, forbidden)) {
            XenditStub.clearHttpError(seeded.requestId());
        }
        reconciler.reconcile();
        for (Seeded seeded : List.of(notFound, serverError, unauthorized, forbidden, malformed, timeout)) {
            assertEquals("PAID", paymentStatus(seeded.id()));
            assertEquals(1, eventCount(seeded.id()));
        }
    }

    @Test
    void oneFailingRowDoesNotAbortTheBatch() {
        Seeded first = seedEligible("ACCEPTED", "SUCCEEDED");
        Seeded broken = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.mapHttpError(broken.requestId(), 500);
        Seeded last = seedEligible("AMBIGUOUS", "SUCCEEDED");

        reconciler.reconcile();

        assertEquals("PAID", paymentStatus(first.id()));
        assertEquals("PENDING", paymentStatus(broken.id()));
        assertEquals(0, eventCount(broken.id()));
        assertEquals("PAID", paymentStatus(last.id()));
    }

    // ---------- correlation ----------

    @Test
    void correlationMismatchRecordsRejectedAndDoesNotSettle() {
        // reference mismatch: provider answers with another reference
        Seeded referenceMismatch = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.mapRequest(referenceMismatch.requestId(), "other-ref", "SUCCEEDED");

        // amount mismatch: local amount 9999, provider reports 10000
        String requestId = nextRequestId();
        String reference = "nv_pay_rec_" + (++counter);
        long amountMismatch = seedPayment("ACCEPTED", requestId, reference,
                clock.instant().minus(Duration.ofHours(2)), 9999);
        XenditStub.mapRequest(requestId, reference, "SUCCEEDED");

        reconciler.reconcile();

        assertEquals("PENDING", paymentStatus(referenceMismatch.id()));
        assertEquals("PENDING", paymentStatus(amountMismatch));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT result FROM payment_events WHERE payment_id=?", String.class, referenceMismatch.id()));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT result FROM payment_events WHERE payment_id=?", String.class, amountMismatch));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action='PAYMENT_SETTLED' AND entity_id IN (?,?)",
                Integer.class, referenceMismatch.id(), amountMismatch));
    }

    // ---------- BR-21 and terminal local state ----------

    @Test
    void voidedOrderSucceededPaysPaymentKeepsOrderVoided() {
        jdbc.update("UPDATE orders SET status='VOIDED' WHERE id=?", orderId);
        Seeded seeded = seedEligible("ACCEPTED", "SUCCEEDED");

        reconciler.reconcile();

        assertEquals("PAID", paymentStatus(seeded.id()));
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action='PAYMENT_ON_CLOSED_ORDER' AND entity_id=?
                """, Integer.class, seeded.id()));
    }

    @Test
    void terminalLocalStateIsNotResurrectedAndRecordsRejected() throws Exception {
        Seeded seeded = seedEligible("AMBIGUOUS", "SUCCEEDED");
        XenditStub.setMode(XenditStub.Mode.STATUS_BLOCKED);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> running = pool.submit(() -> reconciler.reconcile());
            assertTrue(XenditStub.awaitStatusRequest(10), "reconciler must reach the Status API");
            // the row becomes terminal while the provider call is in flight (webhook race)
            jdbc.update("UPDATE payments SET status='FAILED' WHERE id=?", seeded.id());
            XenditStub.releaseBlock();
            assertNotNull(running.get(15, TimeUnit.SECONDS));
        } finally {
            XenditStub.releaseBlock();
            pool.shutdownNow();
        }

        assertEquals("FAILED", paymentStatus(seeded.id()));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT result FROM payment_events WHERE payment_id=?", String.class, seeded.id()));
    }

    // ---------- candidate rules ----------

    @Test
    void sendingAndMissingRequestIdRowsAreNeverQueriedOrChanged() {
        long sending = seedPayment("SENDING", nextRequestId(), nextReference(),
                clock.instant().minus(Duration.ofHours(2)), 10000);
        long missingRequestId = seedPayment("ACCEPTED", null, nextReference(),
                clock.instant().minus(Duration.ofHours(2)), 10000);

        reconciler.reconcile();

        assertEquals(0, XenditStub.count(), "unqueryable rows must never reach the Status API");
        assertEquals("PENDING", paymentStatus(sending));
        assertEquals("PENDING", paymentStatus(missingRequestId));
        assertEquals(0, eventCount(sending));
        assertEquals(0, eventCount(missingRequestId));
    }

    @Test
    void clockControlsMinAgeEligibility() {
        Seeded young = seedPaymentSeeded("ACCEPTED", "SUCCEEDED", clock.instant());

        reconciler.reconcile();
        assertEquals(0, XenditStub.count(), "row younger than minAge must not be selected");

        clock.advance(Duration.ofHours(2));
        reconciler.reconcile();
        assertEquals(1, XenditStub.count());
        assertEquals("PAID", paymentStatus(young.id()));
    }

    @Test
    void batchSizeRespectedAndOldestFirst() {
        Instant base = clock.instant().minus(Duration.ofHours(5));
        Seeded oldest = seedPaymentSeeded("ACCEPTED", "SUCCEEDED", base);
        Seeded middle = seedPaymentSeeded("ACCEPTED", "SUCCEEDED", base.plus(Duration.ofHours(1)));
        Seeded newest = seedPaymentSeeded("ACCEPTED", "SUCCEEDED", base.plus(Duration.ofHours(2)));

        properties.setBatchSize(2);
        try {
            reconciler.reconcile();
        } finally {
            properties.setBatchSize(50);
        }

        assertEquals(2, XenditStub.count());
        assertEquals("PAID", paymentStatus(oldest.id()));
        assertEquals("PAID", paymentStatus(middle.id()));
        assertEquals("PENDING", paymentStatus(newest.id()));
    }

    @Test
    void noHttpCallWhileAOrderRowLockIsHeldElsewhere() throws Exception {
        Seeded seeded = seedEligible("ACCEPTED", "SUCCEEDED");
        XenditStub.setMode(XenditStub.Mode.STATUS_BLOCKED);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> running = pool.submit(() -> reconciler.reconcile());
            assertTrue(XenditStub.awaitStatusRequest(10), "reconciler must be inside the Status API call");
            // A second connection can take the order row lock with NOWAIT: the reconciler holds none.
            Long locked = jdbc.queryForObject("SELECT id FROM orders WHERE id=? FOR UPDATE NOWAIT",
                    Long.class, orderId);
            assertEquals(orderId, locked);
            XenditStub.releaseBlock();
            assertNotNull(running.get(15, TimeUnit.SECONDS));
        } finally {
            XenditStub.releaseBlock();
            pool.shutdownNow();
        }
        assertEquals("PAID", paymentStatus(seeded.id()));
    }

    // ---------- concurrency and atomicity ----------

    @Test
    void twoReconcileRunsSettleExactlyOnce() throws Exception {
        Seeded seeded = seedEligible("AMBIGUOUS", "SUCCEEDED");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> reconciler.reconcile());
            Future<Integer> b = pool.submit(() -> reconciler.reconcile());
            assertNotNull(a.get(20, TimeUnit.SECONDS));
            assertNotNull(b.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals("PAID", paymentStatus(seeded.id()));
        assertEquals(1, eventCount(seeded.id()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action='PAYMENT_SETTLED' AND entity_id=?
                """, Integer.class, seeded.id()));
    }

    @Test
    void reconcileRacingWebhookSettlesExactlyOnce() throws Exception {
        Seeded seeded = seedEligible("AMBIGUOUS", "SUCCEEDED");
        String body = webhookPayload(seeded);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> reconcileRun = pool.submit(() -> reconciler.reconcile());
            Future<Integer> webhook = pool.submit(() -> mvc.perform(post("/api/v1/webhooks/xendit")
                            .header("x-callback-token", "test-callback-token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus());
            assertNotNull(reconcileRun.get(20, TimeUnit.SECONDS));
            assertEquals(200, webhook.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals("PAID", paymentStatus(seeded.id()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_events WHERE payment_id=? AND result='PROCESSED'
                """, Integer.class, seeded.id()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action='PAYMENT_SETTLED' AND entity_id=?
                """, Integer.class, seeded.id()));
    }

    @Test
    void eventInsertFailureRollsBackEffectAndRetrySucceeds() {
        Seeded seeded = seedEligible("ACCEPTED", "SUCCEEDED");
        jdbc.execute("CREATE FUNCTION test_fail_payment_event_insert() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced event insert failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_fail_payment_event_insert BEFORE INSERT ON payment_events "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_payment_event_insert()");

        reconciler.reconcile();

        // the whole write transaction rolled back: no payment effect, no event, no audit
        assertEquals("PENDING", paymentStatus(seeded.id()));
        assertEquals(0, eventCount(seeded.id()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action='PAYMENT_SETTLED' AND entity_id=?
                """, Integer.class, seeded.id()));

        jdbc.execute("DROP TRIGGER test_fail_payment_event_insert ON payment_events");
        jdbc.execute("DROP FUNCTION test_fail_payment_event_insert()");

        reconciler.reconcile();
        assertEquals("PAID", paymentStatus(seeded.id()));
        assertEquals(1, eventCount(seeded.id()));
    }

    // ---------- scheduler, expiry webhook, log hygiene ----------

    @Test
    void schedulerIsDisabledByDefaultInTheNormalTestContext() {
        assertFalse(properties.isEnabled());
        assertEquals(0, context.getBeanNamesForType(PaymentReconciliationScheduler.class).length);
        assertEquals(0, context.getBeanNamesForType(PaymentReconciliationSchedulingConfiguration.class).length);
        assertEquals(0, XenditStub.count());
    }

    @Test
    void paymentRequestExpiryCallbackIsAcknowledgedWithoutReconciliationSideEffects() throws Exception {
        Seeded seeded = seedEligible("ACCEPTED", "SUCCEEDED");
        String expiry = "{\"event\":\"payment_request.expiry\",\"data\":{\"payment_request_id\":\""
                + seeded.requestId() + "\",\"reference_id\":\"" + seeded.reference()
                + "\",\"status\":\"EXPIRED\"}}";

        mvc.perform(post("/api/v1/webhooks/xendit")
                        .header("x-callback-token", "test-callback-token")
                        .contentType(MediaType.APPLICATION_JSON).content(expiry))
                .andExpect(status().isOk());

        assertEquals("PENDING", paymentStatus(seeded.id()));
        assertEquals(0, eventCount(seeded.id()));
        assertEquals(0, XenditStub.count(), "expiry payload must not be parsed or looked up");
    }

    @Test
    void reconciliationLogsContainNoSecretAuthorizationBodyOrQr() {
        seedEligible("ACCEPTED", "SUCCEEDED");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(PaymentReconciliationService.class);
        Logger webhookLogger = (Logger) LoggerFactory.getLogger(
                com.nightvenue.api.payment.XenditWebhookService.class);
        serviceLogger.addAppender(appender);
        webhookLogger.addAppender(appender);
        try {
            reconciler.reconcile();
        } finally {
            serviceLogger.detachAppender(appender);
            webhookLogger.detachAppender(appender);
        }
        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(logs.contains(XenditStub.SECRET), "secret leaked into logs");
        assertFalse(logs.toLowerCase().contains("authorization"), "authorization header leaked into logs");
        assertFalse(logs.contains("Basic "), "basic auth header leaked into logs");
        assertFalse(logs.contains(XenditStub.QR), "QR string leaked into logs");
        assertFalse(logs.contains("\"request_amount\""), "raw body leaked into logs");
    }

    // ---------- helpers ----------

    private record Seeded(long id, String requestId, String reference) {
    }

    private Seeded seedEligible(String chargeState, String providerStatus) {
        return seedPaymentSeeded(chargeState, providerStatus, clock.instant().minus(Duration.ofHours(2)));
    }

    private Seeded seedPaymentSeeded(String chargeState, String providerStatus, Instant chargeStartedAt) {
        String requestId = nextRequestId();
        String reference = nextReference();
        long id = seedPayment(chargeState, requestId, reference, chargeStartedAt, 10000);
        XenditStub.mapRequest(requestId, reference, providerStatus);
        return new Seeded(id, requestId, reference);
    }

    private long seedPayment(String chargeState, String requestId, String reference,
                             Instant chargeStartedAt, long amount) {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.queryForObject("""
                INSERT INTO payments(order_id,venue_id,business_date,method,amount,status,provider,
                                     provider_reference_id,provider_request_id,charge_state,
                                     charge_started_at,charge_attempts,created_by,created_at,updated_at,version)
                VALUES(?,?,CURRENT_DATE,'QRIS',?,'PENDING','XENDIT',?,?,?,?,1,?,?,?,0) RETURNING id
                """, Long.class, orderId, venueId, amount, reference, requestId, chargeState,
                Timestamp.from(chargeStartedAt), userId, now, now);
    }

    private String nextReference() {
        return "nv_pay_rec_" + (++counter);
    }

    private static String nextRequestId() {
        return "pr-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String paymentStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, id);
    }

    private String chargeState(long id) {
        return jdbc.queryForObject("SELECT charge_state FROM payments WHERE id=?", String.class, id);
    }

    private int eventCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?", Integer.class, id);
    }

    private static String webhookPayload(Seeded seeded) {
        return "{\"event\":\"payment.capture\",\"data\":{\"payment_request_id\":\"" + seeded.requestId()
                + "\",\"reference_id\":\"" + seeded.reference() + "\",\"payment_id\":\"pay-test\","
                + "\"request_amount\":10000,\"currency\":\"IDR\",\"country\":\"ID\","
                + "\"channel_code\":\"QRIS\",\"type\":\"PAY\",\"status\":\"SUCCEEDED\"}}";
    }

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

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
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
