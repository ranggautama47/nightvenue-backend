package com.nightvenue.api.shift;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ShiftCutoffTestSupport.CutoffClockConfig.class})
class ShiftCutoffIntegrationTests extends ShiftCutoffTestSupport {

    @Test
    void c1_staleOpenShiftFlipsToManagerReviewWithNullActorAudit() {
        // Current business date is 2026-11-17 (NOW = 2026-11-17T12:00:00Z UTC, Asia/Jakarta = 19:00, cutoff 6 => 2026-11-17)
        // Stale shift: business date 2026-11-16
        LocalDate staleDate = LocalDate.of(2026, 11, 16);
        long shiftId = insertOpenShift(venueJakarta, cashier1, staleDate);

        int count = cutoffService.runCutoff();
        assertThat(count).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(row.get("status")).isEqualTo("MANAGER_REVIEW");
        assertThat(((Timestamp) row.get("forced_review_at")).toInstant()).isEqualTo(DEFAULT_NOW);
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
        assertThat(row.get("expected_cash")).isNull();
        assertThat(row.get("actual_cash")).isNull();
        assertThat(row.get("variance")).isNull();
        assertThat(row.get("submitted_at")).isNull();
        assertThat(row.get("submit_note")).isNull();
        assertThat(row.get("reviewed_by")).isNull();
        assertThat(row.get("reviewed_at")).isNull();
        assertThat(row.get("review_note")).isNull();

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ?",
                Integer.class, shiftId);
        assertThat(auditCount).isEqualTo(1);

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT * FROM audit_logs WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ?", shiftId);
        assertThat(audit.get("action")).isEqualTo("SHIFT_FORCED_REVIEW");
        assertThat(audit.get("actor_user_id")).isNull();
        assertThat(audit.get("details").toString()).contains("\"business_date\": \"2026-11-16\"");
    }

    @Test
    void c2_currentBusinessDateShiftUntouched() {
        LocalDate currentDate = LocalDate.of(2026, 11, 17);
        long shiftId = insertOpenShift(venueJakarta, cashier1, currentDate);

        int count = cutoffService.runCutoff();
        assertThat(count).isZero();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("forced_review_at")).isNull();
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(0L);

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'CASHIER_SHIFT' AND entity_id = ?",
                Integer.class, shiftId);
        assertThat(auditCount).isZero();
    }

    @Test
    void c3_rolloverBoundaryOneSecondBeforeVsAtCutoff() {
        // Venue Jakarta: UTC+7, cutoff hour 6.
        // Rollover moment: 06:00:00 Jakarta time = 23:00:00 UTC previous day.
        // On 2026-11-17 at 05:59:59 Jakarta = 2026-11-16T22:59:59Z UTC:
        // current business date is 2026-11-16.
        // Shift with business_date 2026-11-16 is current => NOT stale.
        Instant oneSecondBeforeCutoff = Instant.parse("2026-11-16T22:59:59Z");
        testClock.set(oneSecondBeforeCutoff);

        long shiftId = insertOpenShift(venueJakarta, cashier1, LocalDate.of(2026, 11, 16));

        int countBefore = cutoffService.runCutoff();
        assertThat(countBefore).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, shiftId))
                .isEqualTo("OPEN");

        // At cutoff moment: 06:00:00 Jakarta = 2026-11-16T23:00:00Z UTC:
        // current business date rolls over to 2026-11-17.
        // Shift with business_date 2026-11-16 is now < 2026-11-17 => stale!
        Instant atCutoff = Instant.parse("2026-11-16T23:00:00Z");
        testClock.set(atCutoff);

        int countAt = cutoffService.runCutoff();
        assertThat(countAt).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, shiftId))
                .isEqualTo("MANAGER_REVIEW");
    }

    @Test
    void c4_nonOpenShiftsUntouched() {
        LocalDate oldDate = LocalDate.of(2026, 11, 10);
        long subId = insertShiftWithStatus(venueJakarta, cashier1, oldDate, "SUBMITTED");
        long revId = insertShiftWithStatus(venueJakarta, cashier2, oldDate, "MANAGER_REVIEW");
        long recId = insertShiftWithStatus(venueJakarta, cashier3, oldDate, "RECONCILED");

        int count = cutoffService.runCutoff();
        assertThat(count).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, subId))
                .isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, revId))
                .isEqualTo("MANAGER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, recId))
                .isEqualTo("RECONCILED");

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id IN (?, ?, ?)",
                Integer.class, subId, revId, recId);
        assertThat(auditCount).isZero();
    }

    @Test
    void c5_twoVenuesDifferentTimezonesOnlyStaleFlips() {
        // Venue Jakarta (UTC+7, cutoff 6). At 2026-11-16T21:30:00Z:
        // Jakarta time: 2026-11-17 04:30 => minus 6h = 2026-11-16 22:30 => business date = 2026-11-16.
        // Venue Jayapura (UTC+9, cutoff 6). At 2026-11-16T21:30:00Z:
        // Jayapura time: 2026-11-17 06:30 => minus 6h = 2026-11-17 00:30 => business date = 2026-11-17.
        Instant instant = Instant.parse("2026-11-16T21:30:00Z");
        testClock.set(instant);

        // Both venues have an OPEN shift for business date 2026-11-16:
        long jakartaShift = insertOpenShift(venueJakarta, cashier1, LocalDate.of(2026, 11, 16));
        long jayapuraShift = insertOpenShift(venueJayapura, cashier2, LocalDate.of(2026, 11, 16));

        int flipped = cutoffService.runCutoff();
        assertThat(flipped).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, jakartaShift))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, jayapuraShift))
                .isEqualTo("MANAGER_REVIEW");
    }

    @Test
    void c6_idempotentBackToBackRuns() {
        LocalDate staleDate = LocalDate.of(2026, 11, 16);
        long shiftId = insertOpenShift(venueJakarta, cashier1, staleDate);

        int first = cutoffService.runCutoff();
        assertThat(first).isEqualTo(1);

        Map<String, Object> stateAfterFirst = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        Integer auditsAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'",
                Integer.class, shiftId);

        int second = cutoffService.runCutoff();
        assertThat(second).isZero();

        Map<String, Object> stateAfterSecond = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        Integer auditsAfterSecond = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'",
                Integer.class, shiftId);

        assertThat(stateAfterSecond).isEqualTo(stateAfterFirst);
        assertThat(auditsAfterSecond).isEqualTo(auditsAfterFirst).isEqualTo(1);
    }

    @Test
    void c7_multipleStaleShiftsInOneVenueAllFlip() {
        LocalDate staleDate = LocalDate.of(2026, 11, 15);
        long shift1 = insertOpenShift(venueJakarta, cashier1, staleDate);
        long shift2 = insertOpenShift(venueJakarta, cashier2, staleDate);
        long shift3 = insertOpenShift(venueJakarta, cashier3, staleDate);

        int count = cutoffService.runCutoff();
        assertThat(count).isEqualTo(3);

        for (long id : new long[]{shift1, shift2, shift3}) {
            assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, id))
                    .isEqualTo("MANAGER_REVIEW");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'",
                    Integer.class, id)).isEqualTo(1);
        }
    }

    @Test
    void c8_failureIsolationWithInvalidTimezone() {
        long invalidVenue = createVenue("inv", "Invalid Zone Venue", "Invalid/Zone", 6);
        jdbc.update("INSERT INTO user_venues(user_id, venue_id) VALUES (?, ?)", cashier2, invalidVenue);

        long staleInvalidShift = insertOpenShift(invalidVenue, cashier2, LocalDate.of(2026, 11, 15));
        long staleHealthyShift = insertOpenShift(venueJakarta, cashier1, LocalDate.of(2026, 11, 15));

        int count = cutoffService.runCutoff();
        assertThat(count).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, staleInvalidShift))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'",
                Integer.class, staleInvalidShift)).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id = ?", String.class, staleHealthyShift))
                .isEqualTo("MANAGER_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'",
                Integer.class, staleHealthyShift)).isEqualTo(1);
    }

    @Test
    void c9_endToEndAfterJobFlipCashierSubmitConflictManagerReviewReconciled() throws Exception {
        LocalDate staleDate = LocalDate.of(2026, 11, 16);
        long shiftId = insertOpenShift(venueJakarta, cashier1, staleDate);

        int count = cutoffService.runCutoff();
        assertThat(count).isEqualTo(1);

        // Cashier POST submit -> 409 SHIFT_NOT_OPEN
        mvc.perform(post("/api/v1/shifts/" + shiftId + "/submit")
                        .header("Authorization", bearer(cashier1, "CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualCash\": 100, \"note\": \"cashier trying to submit\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("SHIFT_NOT_OPEN"));

        // MANAGER POST review with actualCash and a note -> 200 RECONCILED
        mvc.perform(post("/api/v1/shifts/" + shiftId + "/review")
                        .header("Authorization", bearer(managerId, "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualCash\": 100, \"note\": \"reconciled by manager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECONCILED"))
                .andExpect(jsonPath("$.actualCash").value(100))
                .andExpect(jsonPath("$.expectedCash").value(100))
                .andExpect(jsonPath("$.variance").value(0))
                .andExpect(jsonPath("$.submittedAt").doesNotExist());

        Map<String, Object> finalRow = jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
        assertThat(finalRow.get("status")).isEqualTo("RECONCILED");
        assertThat(finalRow.get("submitted_at")).isNull();
        assertThat(finalRow.get("reviewed_by")).isEqualTo(managerId);
    }

    @Test
    void c11_requestPathFlipsStaleShiftThenJobReturnsZero() throws Exception {
        LocalDate staleDate = LocalDate.of(2026, 11, 16);
        long staleShiftId = insertOpenShift(venueJakarta, cashier1, staleDate);

        // Cashier POST /api/v1/shifts opens a new shift, which flips the stale shift in request path
        mvc.perform(post("/api/v1/shifts")
                        .header("Authorization", bearer(cashier1, "CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\": " + venueJakarta + ", \"openingFloat\": 200}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.businessDate").value("2026-11-17"));

        // Audit was written with cashier1 actor by the request path
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT * FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'", staleShiftId);
        assertThat(audit.get("actor_user_id")).isEqualTo(cashier1);

        // Now job runs: stale shift is already MANAGER_REVIEW, new shift is current business date (2026-11-17)
        int jobCount = cutoffService.runCutoff();
        assertThat(jobCount).isZero();

        Integer totalAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'",
                Integer.class, staleShiftId);
        assertThat(totalAudits).isEqualTo(1);
    }

    @Test
    void c12_jobFlipsStaleShiftThenCashierOpensNewShiftSuccessfully() throws Exception {
        LocalDate staleDate = LocalDate.of(2026, 11, 16);
        long staleShiftId = insertOpenShift(venueJakarta, cashier1, staleDate);

        int count = cutoffService.runCutoff();
        assertThat(count).isEqualTo(1);

        // Proves partial unique index is released: cashier opens new shift successfully -> 201
        mvc.perform(post("/api/v1/shifts")
                        .header("Authorization", bearer(cashier1, "CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\": " + venueJakarta + ", \"openingFloat\": 300}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.businessDate").value("2026-11-17"));

        // Audit count for old shift stays exactly 1 (system forced review)
        Integer totalAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'",
                Integer.class, staleShiftId);
        assertThat(totalAudits).isEqualTo(1);

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT * FROM audit_logs WHERE entity_id = ? AND action = 'SHIFT_FORCED_REVIEW'", staleShiftId);
        assertThat(audit.get("actor_user_id")).isNull();
    }
}
