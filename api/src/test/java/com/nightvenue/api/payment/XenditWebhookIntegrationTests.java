package com.nightvenue.api.payment;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"nightvenue.seed.enabled=false", "nightvenue.payment.xendit.secret-key=test-secret-not-real", "nightvenue.payment.xendit.read-timeout-ms=400"})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class XenditWebhookIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.nightvenue.api.payment.xendit.XenditProperties xenditProperties;
    private long venueId, userId, orderId, paymentId;
    private String referenceId;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("nightvenue.payment.xendit.base-url", () -> "http://localhost:" + XenditStub.port());
    }

    @BeforeEach void setup() {
        Timestamp now=Timestamp.from(Instant.now());
        String fixture="WEB-"+UUID.randomUUID().toString().substring(0,8);
        venueId=jdbc.queryForObject("INSERT INTO venues(code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at) VALUES(?,?,'Asia/Jakarta',6,true,?,?) RETURNING id",Long.class,fixture,fixture,now,now);
        userId=jdbc.queryForObject("INSERT INTO users(email,password_hash,full_name,role,is_active,version,created_at,updated_at) VALUES(?,'hash','Webhook','CASHIER',true,0,?,?) RETURNING id",Long.class,fixture.toLowerCase()+"@test.local",now,now);
        orderId=jdbc.queryForObject("INSERT INTO orders(venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,opened_at,created_at,updated_at,version) VALUES(?,?,?,CURRENT_DATE,'OPEN',10000,10000,?,?,?,0) RETURNING id",Long.class,venueId,fixture,userId,now,now,now);
        referenceId="nv_pay_1";
        paymentId=jdbc.queryForObject("""
                INSERT INTO payments(order_id,venue_id,business_date,method,amount,status,provider,provider_reference_id,provider_request_id,charge_state,charge_started_at,charge_attempts,created_by,created_at,updated_at,version)
                VALUES(?,?,CURRENT_DATE,'QRIS',10000,'PENDING','XENDIT',?,?,'AMBIGUOUS',?,1,?,?,?,0) RETURNING id
                """,Long.class,orderId,venueId,referenceId,XenditStub.REQUEST_ID,now,userId,now,now);
        XenditStub.reset(XenditStub.Mode.STATUS_404,orderId,null,jdbc);
    }

    @AfterEach void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_payment_event_insert ON payment_events");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_payment_event_insert()");
        jdbc.update("DELETE FROM payment_events WHERE payment_id=?",paymentId);
        jdbc.update("DELETE FROM payments WHERE id=?",paymentId);
        jdbc.update("DELETE FROM orders WHERE id=?",orderId);
        jdbc.update("DELETE FROM users WHERE id=?",userId);
        jdbc.update("DELETE FROM venues WHERE id=?",venueId);
    }

    private String payload() { return payload("payment.capture", referenceId, XenditStub.REQUEST_ID, 10000, "IDR", "ID", "QRIS", "PAY", "pay-test"); }
    private String payload(String event, String reference, String requestId, long amount, String currency,
                           String country, String channel, String type, String paymentId) {
        return "{\"event\":\""+event+"\",\"data\":{\"payment_request_id\":\""+requestId+
                "\",\"reference_id\":\""+reference+"\",\"payment_id\":\""+paymentId+
                "\",\"request_amount\":"+amount+",\"currency\":\""+currency+"\",\"country\":\""+country+
                "\",\"channel_code\":\""+channel+"\",\"type\":\""+type+"\",\"status\":\"SUCCEEDED\"}}";
    }

    @Test void invalidTokenAndMalformedPayload_makeNoWrites() throws Exception {
        mvc.perform(post("/api/v1/webhooks/xendit").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","wrong").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token"," ").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events",Integer.class));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
    }

    @Test void blankServerSideCallbackTokenRejectsEveryCallbackWithoutWritesOrProviderCalls() throws Exception {
        String original = xenditProperties.getCallbackToken();
        xenditProperties.setCallbackToken("  ");
        try {
            mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token")
                    .contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/webhooks/xendit")
                    .contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isUnauthorized());
        } finally {
            xenditProperties.setCallbackToken(original);
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?",Integer.class,paymentId));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(0,XenditStub.count(),"blank server token must never reach the Status API");
    }

    @Test void paymentRequestExpiryCallbackIsAcknowledgedWithoutChanges() throws Exception {
        String expiry="{\"event\":\"payment_request.expiry\",\"data\":{\"payment_request_id\":\""+XenditStub.REQUEST_ID+
                "\",\"reference_id\":\""+referenceId+"\",\"status\":\"EXPIRED\"}}";
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token")
                .contentType(MediaType.APPLICATION_JSON).content(expiry)).andExpect(status().isOk());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?",Integer.class,paymentId));
        assertEquals(0,XenditStub.count(),"undocumented expiry payload must not trigger a Status API call");
    }

    @Test void paymentFailureOnSendingRowWritesV9ValidFailedOutcome() throws Exception {
        jdbc.update("UPDATE payments SET charge_state='SENDING',provider_request_id=NULL WHERE id=?",paymentId);
        XenditStub.reset(XenditStub.Mode.STATUS_FAILED,orderId,null,jdbc);
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("payment.failure",referenceId,XenditStub.REQUEST_ID,10000,"IDR","ID","QRIS","PAY","pay-failed")))
                .andExpect(status().isOk());
        // The settlement CAS rewrites SENDING -> AMBIGUOUS in the same UPDATE, so the
        // V9 coupling (SENDING requires PENDING) is never violated (no SQLSTATE 23514).
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals("AMBIGUOUS",jdbc.queryForObject("SELECT charge_state FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(XenditStub.REQUEST_ID,jdbc.queryForObject("SELECT provider_request_id FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=? AND result='PROCESSED'",Integer.class,paymentId));
    }

    @Test void status404_returns503AndDoesNotFailPaymentOrWriteEvent() throws Exception {
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isServiceUnavailable());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?",Integer.class,paymentId));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
    }

    @Test void sendingToPaidUsesV9ValidCoupledTransition() throws Exception {
        jdbc.update("UPDATE payments SET charge_state='SENDING',provider_request_id=NULL WHERE id=?",paymentId);
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isOk());
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals("AMBIGUOUS",jdbc.queryForObject("SELECT charge_state FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=? AND result='PROCESSED'",Integer.class,paymentId));
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,orderId));
    }

    @Test void acceptedPaymentCanSettle() throws Exception {
        jdbc.update("UPDATE payments SET charge_state='ACCEPTED' WHERE id=?",paymentId);
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isOk());
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT charge_state FROM payments WHERE id=?",String.class,paymentId));
    }

    @Test void verifiedFailureAndExpiryApplyAllowedPendingTransitions() throws Exception {
        XenditStub.reset(XenditStub.Mode.STATUS_FAILED,orderId,null,jdbc);
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON)
                .content(payload("payment.failure",referenceId,XenditStub.REQUEST_ID,10000,"IDR","ID","QRIS","PAY","pay-failed"))).andExpect(status().isOk());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));

        jdbc.update("UPDATE payments SET status='PENDING',charge_state='AMBIGUOUS' WHERE id=?",paymentId);
        XenditStub.reset(XenditStub.Mode.STATUS_EXPIRED,orderId,null,jdbc);
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON)
                .content(payload("payment.failure",referenceId,XenditStub.REQUEST_ID,10000,"IDR","ID","QRIS","PAY","pay-expired"))).andExpect(status().isOk());
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
    }

    @Test void verifiedSuccessOnRejectedRowDoesNotPay() throws Exception {
        jdbc.update("UPDATE payments SET status='FAILED',charge_state='REJECTED',provider_request_id=NULL WHERE id=?",paymentId);
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isOk());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals("REJECTED",jdbc.queryForObject("SELECT result FROM payment_events WHERE payment_id=?",String.class,paymentId));
    }

    @Test void terminalPaymentsAreRejectedAndNeverResurrected() throws Exception {
        String[] states={"FAILED","EXPIRED","CANCELLED"};
        int index=0;
        for(String terminal:states) {
            jdbc.update("UPDATE payments SET status=?,charge_state='AMBIGUOUS' WHERE id=?",terminal,paymentId);
            XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
            String body=payload("payment.capture",referenceId,XenditStub.REQUEST_ID,10000,"IDR","ID","QRIS","PAY","pay-test-"+(index++));
            mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            assertEquals(terminal,jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        }
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=? AND result='REJECTED'",Integer.class,paymentId));
    }

    @Test void voidedOrderSettlementPaysPaymentAndKeepsOrderVoided() throws Exception {
        jdbc.update("UPDATE orders SET status='VOIDED' WHERE id=?",orderId);
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isOk());
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals("VOIDED",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,orderId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='PAYMENT_ON_CLOSED_ORDER' AND entity_id=?",Integer.class,paymentId));
    }

    @Test void eventInsertFailureRollsBackSettlementAndRetrySucceeds() throws Exception {
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        jdbc.execute("CREATE FUNCTION test_fail_payment_event_insert() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'forced event insert failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_fail_payment_event_insert BEFORE INSERT ON payment_events FOR EACH ROW EXECUTE FUNCTION test_fail_payment_event_insert()");
        assertThrows(Exception.class, () -> mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?",Integer.class,paymentId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action IN ('PAYMENT_SETTLED','PAYMENT_STATUS_CHANGED')",Integer.class,paymentId));
        jdbc.execute("DROP TRIGGER test_fail_payment_event_insert ON payment_events");
        jdbc.execute("DROP FUNCTION test_fail_payment_event_insert()");
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isOk());
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?",Integer.class,paymentId));
    }

    @Test void statusApiFailuresNeverWriteOrChangePayment() throws Exception {
        for (XenditStub.Mode mode : new XenditStub.Mode[]{XenditStub.Mode.STATUS_500, XenditStub.Mode.STATUS_401,
                XenditStub.Mode.STATUS_403, XenditStub.Mode.STATUS_MALFORMED, XenditStub.Mode.STATUS_TIMEOUT}) {
            XenditStub.reset(mode,orderId,null,jdbc);
            mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token")
                    .contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isServiceUnavailable());
        }
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?",Integer.class,paymentId));
    }

    @Test void referenceCorrelationRecoversNullRequestIdAndStatusReferenceFallbackWorks() throws Exception {
        jdbc.update("UPDATE payments SET charge_state='SENDING',provider_request_id=NULL WHERE id=?",paymentId);
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        String withoutReference=payload().replace("\"reference_id\":\""+referenceId+"\",","");
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(withoutReference)).andExpect(status().isOk());
        assertEquals(XenditStub.REQUEST_ID,jdbc.queryForObject("SELECT provider_request_id FROM payments WHERE id=?",String.class,paymentId));
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
    }

    @Test void verifiedUnmatchedCallbackIsAcknowledgedWithoutEventRow() throws Exception {
        XenditStub.reset(XenditStub.Mode.STATUS_UNMATCHED,orderId,null,jdbc);
        String unknown=payload("payment.capture","unknown-ref","pr-unknown",10000,"IDR","ID","QRIS","PAY","pay-unknown");
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(unknown)).andExpect(status().isOk());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events",Integer.class));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
    }

    @Test void verifiedCallbackFieldMismatchRecordsRejectedWithoutPaymentMutation() throws Exception {
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        String mismatch=payload("payment.capture",referenceId,XenditStub.REQUEST_ID,9999,"IDR","ID","QRIS","PAY","pay-test");
        mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(mismatch)).andExpect(status().isOk());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals("REJECTED",jdbc.queryForObject("SELECT result FROM payment_events WHERE payment_id=?",String.class,paymentId));
    }

    @Test void duplicateDeliveryHasOneEffectAndOneCanonicalEvent() throws Exception {
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        for(int i=0;i<2;i++) mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isOk());
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?",Integer.class,paymentId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='PAYMENT_SETTLED'",Integer.class,paymentId));
    }

    @Test void concurrentDuplicateDeliveryHasOneCanonicalSettlement() throws Exception {
        XenditStub.reset(XenditStub.Mode.STATUS_SUCCESS,orderId,null,jdbc);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(() -> mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andReturn());
            var b=pool.submit(() -> mvc.perform(post("/api/v1/webhooks/xendit").header("x-callback-token","test-callback-token").contentType(MediaType.APPLICATION_JSON).content(payload())).andReturn());
            assertEquals(200,a.get(10,TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(200,b.get(10,TimeUnit.SECONDS).getResponse().getStatus());
        } finally { pool.shutdownNow(); }
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paymentId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=?",Integer.class,paymentId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='PAYMENT_SETTLED'",Integer.class,paymentId));
    }
}
