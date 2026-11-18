package com.nightvenue.api.shift;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "nightvenue.seed.enabled=false",
        "nightvenue.shift.cutoff.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public class ShiftConcurrencyIntegrationTests extends ShiftConcurrencyTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * S1 — double submit: same cashier, 2 tasks with notes "count A"/"count B", actualCash=openingFloat=1000.
     * Statuses sorted == [200,409]; 409 body contains SHIFT_NOT_OPEN;
     * DB: SUBMITTED, version == initial+1, expected 1000, actual 1000, variance 0,
     * submit_note == winner's note (winner = task with 200); exactly one SHIFT_SUBMITTED audit (actor cashier).
     */
    @Test
    void s1_doubleSubmit() throws Exception {
        // Compute business date once per test (note: a test straddling 06:00 WIB could flake)
        LocalDate bDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        long shiftId = shiftRepository.insertOpenShift(venueAId, cashierA1, bDate, 1000, Instant.now()).id();
        long initialVersion = getShiftVersion(shiftId);

        Callable<Outcome> taskA = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/submit")
                            .header("Authorization", bearer(cashierA1, "CASHIER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actualCash\":1000,\"note\":\"count A\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskB = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/submit")
                            .header("Authorization", bearer(cashierA1, "CASHIER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actualCash\":1000,\"note\":\"count B\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        List<Outcome> outcomes = race(List.of(taskA, taskB), TIMEOUT);
        List<Integer> statuses = outcomes.stream().map(Outcome::status).sorted().toList();
        assertThat(statuses).containsExactly(200, 409);

        Outcome winner = outcomes.get(0).status() == 200 ? outcomes.get(0) : outcomes.get(1);
        Outcome loser = outcomes.get(0).status() == 409 ? outcomes.get(0) : outcomes.get(1);
        assertThat(loser.body()).contains("SHIFT_NOT_OPEN");

        String expectedNote = outcomes.get(0).status() == 200 ? "count A" : "count B";

        Map<String, Object> shift = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(shift.get("status")).isEqualTo("SUBMITTED");
        assertThat(((Number) shift.get("version")).longValue()).isEqualTo(initialVersion + 1);
        assertThat(((Number) shift.get("expected_cash")).longValue()).isEqualTo(1000L);
        assertThat(((Number) shift.get("actual_cash")).longValue()).isEqualTo(1000L);
        assertThat(((Number) shift.get("variance")).longValue()).isEqualTo(0L);
        assertThat(shift.get("submit_note")).isEqualTo(expectedNote);

        Integer auditCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_SUBMITTED'
                  AND actor_user_id = ?
                """, Integer.class, shiftId, cashierA1);
        assertThat(auditCount).isEqualTo(1);
    }

    /**
     * S2 — double review: SUBMITTED shift (expected=actual=1000, variance 0), 2 managers.
     * Statuses [200,409]; 409 body SHIFT_NOT_REVIEWABLE; RECONCILED; version == initial+1;
     * reviewed_by == winner manager; review_note == winner's note;
     * exactly one SHIFT_RECONCILED audit with actor == winner.
     */
    @Test
    void s2_doubleReview() throws Exception {
        LocalDate bDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        long shiftId = insertSubmittedShift(venueAId, cashierA1, bDate, 1000, 1000, 1000, 0, "orig note");
        long initialVersion = getShiftVersion(shiftId);

        Callable<Outcome> taskM1 = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                            .header("Authorization", bearer(managerA1, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"review by M1\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskM2 = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                            .header("Authorization", bearer(managerA2, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"review by M2\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        List<Outcome> outcomes = race(List.of(taskM1, taskM2), TIMEOUT);
        List<Integer> statuses = outcomes.stream().map(Outcome::status).sorted().toList();
        assertThat(statuses).containsExactly(200, 409);

        Outcome loser = outcomes.get(0).status() == 409 ? outcomes.get(0) : outcomes.get(1);
        assertThat(loser.body()).contains("SHIFT_NOT_REVIEWABLE");

        long winnerManager = outcomes.get(0).status() == 200 ? managerA1 : managerA2;
        String winnerNote = outcomes.get(0).status() == 200 ? "review by M1" : "review by M2";

        Map<String, Object> shift = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(shift.get("status")).isEqualTo("RECONCILED");
        assertThat(((Number) shift.get("version")).longValue()).isEqualTo(initialVersion + 1);
        assertThat(((Number) shift.get("reviewed_by")).longValue()).isEqualTo(winnerManager);
        assertThat(shift.get("review_note")).isEqualTo(winnerNote);

        Integer auditCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_RECONCILED'
                  AND actor_user_id = ?
                """, Integer.class, shiftId, winnerManager);
        assertThat(auditCount).isEqualTo(1);
    }

    /**
     * S3 — submit vs CASH payment: opening_float 1000, order total 5000, submit actualCash 6000,
     * payment CASH 5000 with fresh Idempotency-Key.
     * Submit MUST be 200. Payment is 201 or 409(SHIFT_NOT_OPEN).
     * If 201: expected_cash 6000, variance 0, one payment row (shift_id = shift, PAID, CASH, 5000),
     * one PAYMENT_CREATED audit for the fixture venue.
     * If 409: expected 1000, variance 5000, zero payment rows for the order, zero PAYMENT_CREATED.
     * In both: SUBMITTED, version == initial+1,
     * expected_cash == opening_float + COALESCE(SUM(amount) WHERE shift_id=? AND method='CASH' AND status='PAID',0)
     * computed by SQL, variance == actual - expected, exactly one SHIFT_SUBMITTED audit.
     */
    @Test
    void s3_submitVsCashPayment() throws Exception {
        LocalDate bDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        long shiftId = shiftRepository.insertOpenShift(venueAId, cashierA1, bDate, 1000, Instant.now()).id();
        long orderId = insertOrder(venueAId, cashierA1, bDate, 5000);
        long initialVersion = getShiftVersion(shiftId);

        Callable<Outcome> taskSubmit = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/submit")
                            .header("Authorization", bearer(cashierA1, "CASHIER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actualCash\":6000,\"note\":\"submit cash\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskPayment = () -> {
            MvcResult res = mvc.perform(post("/api/v1/orders/" + orderId + "/payments")
                            .header("Authorization", bearer(cashierA1, "CASHIER"))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"method\":\"CASH\",\"amount\":5000}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        List<Outcome> outcomes = race(List.of(taskSubmit, taskPayment), TIMEOUT);
        Outcome submitOutcome = outcomes.get(0);
        Outcome paymentOutcome = outcomes.get(1);

        assertThat(submitOutcome.status()).isEqualTo(200);
        assertThat(paymentOutcome.status()).isIn(201, 409);
        if (paymentOutcome.status() == 409) {
            assertThat(paymentOutcome.body()).contains("SHIFT_NOT_OPEN");
        }

        Map<String, Object> shift = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(shift.get("status")).isEqualTo("SUBMITTED");
        assertThat(((Number) shift.get("version")).longValue()).isEqualTo(initialVersion + 1);

        Long sqlExpectedCash = jdbc.queryForObject("""
                SELECT 1000 + COALESCE(SUM(amount), 0) FROM payments
                WHERE shift_id = ? AND method = 'CASH' AND status = 'PAID'
                """, Long.class, shiftId);
        assertThat(((Number) shift.get("expected_cash")).longValue()).isEqualTo(sqlExpectedCash);
        assertThat(((Number) shift.get("variance")).longValue()).isEqualTo(6000L - sqlExpectedCash);

        if (paymentOutcome.status() == 201) {
            assertThat(sqlExpectedCash).isEqualTo(6000L);
            assertThat(((Number) shift.get("variance")).longValue()).isEqualTo(0L);

            Integer paymentRows = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payments
                    WHERE order_id = ? AND shift_id = ? AND method = 'CASH' AND status = 'PAID' AND amount = 5000
                    """, Integer.class, orderId, shiftId);
            assertThat(paymentRows).isEqualTo(1);

            Integer paymentAudits = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM audit_logs
                    WHERE venue_id = ? AND action = 'PAYMENT_CREATED' AND entity_type = 'PAYMENT'
                    """, Integer.class, venueAId);
            assertThat(paymentAudits).isEqualTo(1);
        } else {
            assertThat(sqlExpectedCash).isEqualTo(1000L);
            assertThat(((Number) shift.get("variance")).longValue()).isEqualTo(5000L);

            Integer paymentRows = jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId);
            assertThat(paymentRows).isEqualTo(0);

            Integer paymentAudits = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM audit_logs
                    WHERE venue_id = ? AND action = 'PAYMENT_CREATED'
                    """, Integer.class, venueAId);
            assertThat(paymentAudits).isEqualTo(0);
        }

        Integer submitAudits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_SUBMITTED'
                  AND actor_user_id = ?
                """, Integer.class, shiftId, cashierA1);
        assertThat(submitAudits).isEqualTo(1);
    }

    /**
     * S4 — submit vs runCutoff on stale OPEN shift: submit body {"actualCash":1000} -> must be 409 SHIFT_NOT_OPEN.
     * Do NOT assert runCutoff's return value.
     * DB: MANAGER_REVIEW, forced_review_at NOT NULL, version == initial+1,
     * submitted_at/expected_cash/actual_cash/variance/submit_note all NULL,
     * exactly one SHIFT_FORCED_REVIEW audit, actor == cashier OR NULL.
     */
    @Test
    void s4_submitVsRunCutoffOnStaleShift() throws Exception {
        LocalDate currentBDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        LocalDate staleBDate = currentBDate.minusDays(1);
        long shiftId = shiftRepository.insertOpenShift(venueAId, cashierA1, staleBDate, 1000, Instant.now().minusSeconds(86400)).id();
        long initialVersion = getShiftVersion(shiftId);

        Callable<Outcome> taskSubmit = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/submit")
                            .header("Authorization", bearer(cashierA1, "CASHIER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actualCash\":1000}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskCutoff = () -> {
            int count = shiftCutoffService.runCutoff();
            return new Outcome(0, "", count);
        };

        List<Outcome> outcomes = race(List.of(taskSubmit, taskCutoff), TIMEOUT);
        Outcome submitOutcome = outcomes.get(0);

        assertThat(submitOutcome.status()).isEqualTo(409);
        assertThat(submitOutcome.body()).contains("SHIFT_NOT_OPEN");

        Map<String, Object> shift = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(shift.get("status")).isEqualTo("MANAGER_REVIEW");
        assertThat(shift.get("forced_review_at")).isNotNull();
        assertThat(((Number) shift.get("version")).longValue()).isEqualTo(initialVersion + 1);
        assertThat(shift.get("submitted_at")).isNull();
        assertThat(shift.get("expected_cash")).isNull();
        assertThat(shift.get("actual_cash")).isNull();
        assertThat(shift.get("variance")).isNull();
        assertThat(shift.get("submit_note")).isNull();

        List<Map<String, Object>> forcedAudits = jdbc.queryForList("""
                SELECT * FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'
                """, shiftId);
        assertThat(forcedAudits).hasSize(1);
        Object actor = forcedAudits.get(0).get("actor_user_id");
        if (actor != null) {
            assertThat(((Number) actor).longValue()).isEqualTo(cashierA1);
        }
    }

    /**
     * S5 — runCutoff vs POST /api/v1/shifts by the cashier owning a stale OPEN shift:
     * open must be 201 (else STOP and report). Old shift: MANAGER_REVIEW, forced_review_at NOT NULL,
     * version == initial+1, exactly one SHIFT_FORCED_REVIEW (actor cashier OR NULL).
     * Exactly one OPEN shift for that cashier; exactly one SHIFT_OPENED audit (actor cashier) for the new shift.
     * Do not assert runCutoff's return value.
     */
    @Test
    void s5_runCutoffVsOpenShift() throws Exception {
        LocalDate currentBDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        LocalDate staleBDate = currentBDate.minusDays(1);
        long oldShiftId = shiftRepository.insertOpenShift(venueAId, cashierA1, staleBDate, 1000, Instant.now().minusSeconds(86400)).id();
        long initialOldVersion = getShiftVersion(oldShiftId);

        Callable<Outcome> taskOpen = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts")
                            .header("Authorization", bearer(cashierA1, "CASHIER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"venueId\":" + venueAId + ",\"openingFloat\":2000}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskCutoff = () -> {
            int count = shiftCutoffService.runCutoff();
            return new Outcome(0, "", count);
        };

        List<Outcome> outcomes = race(List.of(taskOpen, taskCutoff), TIMEOUT);
        Outcome openOutcome = outcomes.get(0);

        assertThat(openOutcome.status()).isEqualTo(201);

        Map<String, Object> oldShift = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", oldShiftId);
        assertThat(oldShift.get("status")).isEqualTo("MANAGER_REVIEW");
        assertThat(oldShift.get("forced_review_at")).isNotNull();
        assertThat(((Number) oldShift.get("version")).longValue()).isEqualTo(initialOldVersion + 1);

        List<Map<String, Object>> forcedAudits = jdbc.queryForList("""
                SELECT * FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'
                """, oldShiftId);
        assertThat(forcedAudits).hasSize(1);
        Object actor = forcedAudits.get(0).get("actor_user_id");
        if (actor != null) {
            assertThat(((Number) actor).longValue()).isEqualTo(cashierA1);
        }

        List<Map<String, Object>> openShifts = jdbc.queryForList("""
                SELECT * FROM cashier_shifts
                WHERE cashier_id = ? AND status = 'OPEN'
                """, cashierA1);
        assertThat(openShifts).hasSize(1);
        long newShiftId = ((Number) openShifts.get(0).get("id")).longValue();
        assertThat(newShiftId).isNotEqualTo(oldShiftId);

        Integer openAudits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_OPENED'
                  AND actor_user_id = ?
                """, Integer.class, newShiftId, cashierA1);
        assertThat(openAudits).isEqualTo(1);
    }

    /**
     * S6 — owner-manager self-review vs other manager: m1 owns SUBMITTED shift.
     * task0 = m1 review -> 403; task1 = m2 review -> 200 (by TASK INDEX).
     * RECONCILED; reviewed_by == m2; exactly one SHIFT_RECONCILED (actor m2);
     * zero audits by m1; version == initial+1.
     */
    @Test
    void s6_ownerManagerSelfReviewVsOtherManager() throws Exception {
        LocalDate bDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        long shiftId = insertSubmittedShift(venueAId, managerA1, bDate, 1000, 1000, 1000, 0, "m1 shift note");
        long initialVersion = getShiftVersion(shiftId);

        Callable<Outcome> taskM1SelfReview = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                            .header("Authorization", bearer(managerA1, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"self-review\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskM2OtherReview = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                            .header("Authorization", bearer(managerA2, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"other-review\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        List<Outcome> outcomes = race(List.of(taskM1SelfReview, taskM2OtherReview), TIMEOUT);
        assertThat(outcomes.get(0).status()).isEqualTo(403);
        assertThat(outcomes.get(1).status()).isEqualTo(200);

        Map<String, Object> shift = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(shift.get("status")).isEqualTo("RECONCILED");
        assertThat(((Number) shift.get("reviewed_by")).longValue()).isEqualTo(managerA2);
        assertThat(shift.get("review_note")).isEqualTo("other-review");
        assertThat(((Number) shift.get("version")).longValue()).isEqualTo(initialVersion + 1);

        Integer m2Audits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_RECONCILED'
                  AND actor_user_id = ?
                """, Integer.class, shiftId, managerA2);
        assertThat(m2Audits).isEqualTo(1);

        Integer m1Audits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND actor_user_id = ?
                """, Integer.class, shiftId, managerA1);
        assertThat(m1Audits).isEqualTo(0);
    }

    /**
     * S7 — venue isolation: task0 = A-manager review -> 200; tasks 1..3 = B-only manager review -> 404 each.
     * RECONCILED; reviewed_by == A-manager; exactly one SHIFT_RECONCILED;
     * zero audits with actor == B manager; version == initial+1; no venue-B rows changed.
     */
    @Test
    void s7_venueIsolationReview() throws Exception {
        LocalDate bDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        long shiftId = insertSubmittedShift(venueAId, cashierA1, bDate, 1000, 1000, 1000, 0, "venue A shift");
        long initialVersion = getShiftVersion(shiftId);

        Callable<Outcome> taskA = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                            .header("Authorization", bearer(managerA1, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"valid review A\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskB1 = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                            .header("Authorization", bearer(managerB, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"foreign review B1\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskB2 = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                            .header("Authorization", bearer(managerB, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"foreign review B2\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        Callable<Outcome> taskB3 = () -> {
            MvcResult res = mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                            .header("Authorization", bearer(managerB, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"foreign review B3\"}"))
                    .andReturn();
            return new Outcome(res.getResponse().getStatus(), res.getResponse().getContentAsString(), 0);
        };

        List<Outcome> outcomes = race(List.of(taskA, taskB1, taskB2, taskB3), TIMEOUT);
        assertThat(outcomes.get(0).status()).isEqualTo(200);
        assertThat(outcomes.get(1).status()).isEqualTo(404);
        assertThat(outcomes.get(2).status()).isEqualTo(404);
        assertThat(outcomes.get(3).status()).isEqualTo(404);

        Map<String, Object> shift = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(shift.get("status")).isEqualTo("RECONCILED");
        assertThat(((Number) shift.get("reviewed_by")).longValue()).isEqualTo(managerA1);
        assertThat(((Number) shift.get("version")).longValue()).isEqualTo(initialVersion + 1);

        Integer reconciledAudits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_RECONCILED'
                """, Integer.class, shiftId);
        assertThat(reconciledAudits).isEqualTo(1);

        Integer bManagerAudits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE actor_user_id = ?
                """, Integer.class, managerB);
        assertThat(bManagerAudits).isEqualTo(0);

        Integer venueBShifts = jdbc.queryForObject("SELECT COUNT(*) FROM cashier_shifts WHERE venue_id = ?", Integer.class, venueBId);
        assertThat(venueBShifts).isEqualTo(0);
    }

    /**
     * S8 — two parallel runCutoff() over 3 stale shifts of 3 different cashiers (venue A):
     * sum of both counts >= 3 (not == 3; runCutoff is global).
     * Per fixture shift: MANAGER_REVIEW, forced_review_at NOT NULL, version == initial+1,
     * exactly one SHIFT_FORCED_REVIEW with actor NULL.
     */
    @Test
    void s8_parallelRunCutoffThreeStaleShifts() throws Exception {
        LocalDate currentBDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        LocalDate staleBDate = currentBDate.minusDays(1);
        Instant past = Instant.now().minusSeconds(86400);

        long s1 = shiftRepository.insertOpenShift(venueAId, cashierA1, staleBDate, 1000, past).id();
        long s2 = shiftRepository.insertOpenShift(venueAId, cashierA2, staleBDate, 2000, past).id();
        long s3 = shiftRepository.insertOpenShift(venueAId, cashierA3, staleBDate, 3000, past).id();

        Map<Long, Long> initialVersions = new java.util.LinkedHashMap<>();
        initialVersions.put(s1, getShiftVersion(s1));
        initialVersions.put(s2, getShiftVersion(s2));
        initialVersions.put(s3, getShiftVersion(s3));

        Callable<Outcome> taskCutoff1 = () -> {
            int count = shiftCutoffService.runCutoff();
            return new Outcome(0, "", count);
        };

        Callable<Outcome> taskCutoff2 = () -> {
            int count = shiftCutoffService.runCutoff();
            return new Outcome(0, "", count);
        };

        List<Outcome> outcomes = race(List.of(taskCutoff1, taskCutoff2), TIMEOUT);
        int totalFlipped = outcomes.get(0).count() + outcomes.get(1).count();
        assertThat(totalFlipped).isGreaterThanOrEqualTo(3);

        for (long sId : List.of(s1, s2, s3)) {
            Map<String, Object> shift = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", sId);
            assertThat(shift.get("status")).isEqualTo("MANAGER_REVIEW");
            assertThat(shift.get("forced_review_at")).isNotNull();
            assertThat(((Number) shift.get("version")).longValue()).isEqualTo(initialVersions.get(sId) + 1);

            List<Map<String, Object>> forcedAudits = jdbc.queryForList("""
                    SELECT * FROM audit_logs
                    WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'
                    """, sId);
            assertThat(forcedAudits).hasSize(1);
            assertThat(forcedAudits.get(0).get("actor_user_id")).isNull();
        }
    }
}
