package com.nightvenue.api.shift;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ShiftReviewTestSupport.TestClockConfig.class})
class ShiftReviewIntegrationTests extends ShiftReviewTestSupport {
    private static final String REVIEW = "/api/v1/shifts/";

    @Test
    void reconcilesSubmittedShiftUsingStoredCashAndAuditsWithoutNoteText() throws Exception {
        jdbc.update("UPDATE cashier_shifts SET forced_review_at=? WHERE id=?", java.sql.Timestamp.from(NOW), submittedId);
        String before = row(submittedId);
        mvc.perform(review(submittedId, managerId, "MANAGER", "{\"note\":\"  verified  \"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(submittedId))
                        .andExpect(jsonPath("$.venueId").value(venueId)).andExpect(jsonPath("$.cashierId").value(cashierId))
                        .andExpect(jsonPath("$.businessDate").value("2026-11-17"))
                        .andExpect(jsonPath("$.status").value("RECONCILED"))
                        .andExpect(jsonPath("$.openingFloat").value(100)).andExpect(jsonPath("$.submitNote").value("submitted note"))
                        .andExpect(jsonPath("$.expectedCash").value(700)).andExpect(jsonPath("$.actualCash").value(800))
                        .andExpect(jsonPath("$.variance").value(100)).andExpect(jsonPath("$.reviewNote").value("verified"))
                        .andExpect(jsonPath("$.reviewedBy").value(managerId)).andExpect(jsonPath("$.reviewedAt").value(NOW.toString()))
                        .andExpect(jsonPath("$.submittedAt").value(NOW.toString()))
                        .andExpect(jsonPath("$.forcedReviewAt").value(NOW.toString()));
        assertNotEquals(before, row(submittedId));
        assertEquals("RECONCILED", value(submittedId, "status"));
        assertEquals("700", jdbc.queryForObject("SELECT expected_cash FROM cashier_shifts WHERE id=?", String.class, submittedId));
        assertEquals("800", jdbc.queryForObject("SELECT actual_cash FROM cashier_shifts WHERE id=?", String.class, submittedId));
        assertEquals("100", jdbc.queryForObject("SELECT variance FROM cashier_shifts WHERE id=?", String.class, submittedId));
        assertEquals(NOW, jdbc.queryForObject("SELECT reviewed_at FROM cashier_shifts WHERE id=?", (rs, n) -> rs.getTimestamp(1).toInstant(), submittedId));
        assertEquals(NOW, jdbc.queryForObject("SELECT submitted_at FROM cashier_shifts WHERE id=?", (rs, n) -> rs.getTimestamp(1).toInstant(), submittedId));
        assertEquals("submitted note", jdbc.queryForObject("SELECT submit_note FROM cashier_shifts WHERE id=?", String.class, submittedId));
        assertEquals(NOW, jdbc.queryForObject("SELECT forced_review_at FROM cashier_shifts WHERE id=?", (rs, n) -> rs.getTimestamp(1).toInstant(), submittedId));
        assertEquals(1, auditCount(submittedId));
        assertEquals("SUBMITTED", auditDetail(submittedId, "source_status"));
        assertEquals("700", auditDetail(submittedId, "expected_cash"));
        assertEquals("800", auditDetail(submittedId, "actual_cash"));
        assertEquals("100", auditDetail(submittedId, "variance"));
        assertFalse(jdbc.queryForObject("SELECT details::text FROM audit_logs WHERE action='SHIFT_RECONCILED' AND entity_id=?", String.class, submittedId).contains("verified"));
    }

    @Test
    void reconcilesManagerReviewByCalculatingPaidCashAfterLock() throws Exception {
        long id = managerReview(cashierId, NOW);
        paidCash(id, 20);
        paidCash(id, 30);
        mvc.perform(review(id, adminId, "ADMIN", "{\"actualCash\":150,\"note\":\"counted\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECONCILED"))
                .andExpect(jsonPath("$.expectedCash").value(150)).andExpect(jsonPath("$.variance").value(0))
                .andExpect(jsonPath("$.reviewedAt").value(NOW.toString()));
        assertEquals(150L, jdbc.queryForObject("SELECT expected_cash FROM cashier_shifts WHERE id=?", Long.class, id));
        assertEquals("MANAGER_REVIEW", auditDetail(id, "source_status"));
        assertNull(jdbc.queryForObject("SELECT submitted_at FROM cashier_shifts WHERE id=?", (rs, n) -> rs.getTimestamp(1), id));
        assertNull(jdbc.queryForObject("SELECT submit_note FROM cashier_shifts WHERE id=?", String.class, id));
        assertEquals(1, auditCount(id));
    }

    @Test
    void expectedCashIncludesOnlyCashPaidForThisShift() throws Exception {
        long id = managerReview(cashierId, NOW);
        cashPayment(id, "PAID", 20);
        cashPayment(id, "FAILED", 700);
        long otherShift = managerReview(otherCashierId, NOW);
        cashPayment(otherShift, "PAID", 900);
        qrisPaid(cashierId, 500);
        mvc.perform(review(id, managerId, "MANAGER", "{\"actualCash\":120,\"note\":\"counted\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(120))
                .andExpect(jsonPath("$.variance").value(0));
    }

    @Test
    void managerReviewPersistsNegativeAndPositiveVarianceWithNotes() throws Exception {
        long negative = managerReview(cashierId, NOW);
        mvc.perform(review(negative, managerId, "MANAGER", "{\"actualCash\":0,\"note\":\" short \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(100))
                .andExpect(jsonPath("$.actualCash").value(0)).andExpect(jsonPath("$.variance").value(-100))
                .andExpect(jsonPath("$.reviewNote").value("short"));
        assertEquals(1, auditCount(negative));

        long positive = managerReview(otherCashierId, NOW);
        mvc.perform(review(positive, adminId, "ADMIN", "{\"actualCash\":150,\"note\":\" extra \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(100))
                .andExpect(jsonPath("$.variance").value(50)).andExpect(jsonPath("$.reviewNote").value("extra"));
        assertEquals(1, auditCount(positive));
    }

    @Test
    void submittedNegativeVarianceIsPreservedAndAudited() throws Exception {
        long id = submitted(cashierId, 100, 50, -50);
        mvc.perform(review(id, managerId, "MANAGER", "{\"note\":\"  shortage checked  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.variance").value(-50))
                .andExpect(jsonPath("$.reviewNote").value("shortage checked"));
        assertEquals(-50L, jdbc.queryForObject("SELECT variance FROM cashier_shifts WHERE id=?", Long.class, id));
        assertEquals(1, auditCount(id));
    }

    @Test
    void managerReviewWithoutActualCashReturns422AndLeavesRowUnchanged() throws Exception {
        long id = managerReview(cashierId, NOW);
        unchangedError(id, managerId, "MANAGER", "{}", "ACTUAL_CASH_REQUIRED", 422);
    }

    @Test
    void submittedShiftRejectsReplacementActualCash() throws Exception {
        unchangedError(submittedId, managerId, "MANAGER", "{\"actualCash\":800}", "ACTUAL_CASH_NOT_ALLOWED", 422);
    }

    @Test
    void legacySubmittedShiftWithoutCashValuesIsNotReviewable() throws Exception {
        long id = legacySubmitted(cashierId);
        unchangedError(id, managerId, "MANAGER", "{}", "SHIFT_NOT_REVIEWABLE", 409);
    }

    @Test
    void nonzeroVarianceRequiresReviewNote() throws Exception {
        unchangedError(submittedId, managerId, "MANAGER", "{}", "REVIEW_NOTE_REQUIRED", 422);
    }

    @Test
    void blankNoteForRequiredReviewIsRejected() throws Exception {
        unchangedError(submittedId, managerId, "MANAGER", "{\"note\":\"   \"}", "REVIEW_NOTE_REQUIRED", 422);
    }

    @Test
    void zeroVarianceSubmittedShiftMayBeReviewedWithoutNote() throws Exception {
        long id = submitted(cashierId, 100, 100, 0);
        mvc.perform(review(id, managerId, "MANAGER", "{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECONCILED"))
                .andExpect(jsonPath("$.reviewNote").value(org.hamcrest.Matchers.nullValue()));
        assertNull(jdbc.queryForObject("SELECT review_note FROM cashier_shifts WHERE id=?", String.class, id));
    }

    @Test
    void forcedManagerReviewRequiresNoteEvenWhenVarianceIsZero() throws Exception {
        long id = managerReview(cashierId, NOW);
        unchangedError(id, adminId, "ADMIN", "{\"actualCash\":100}", "REVIEW_NOTE_REQUIRED", 422);
        unchangedError(id, adminId, "ADMIN", "{\"actualCash\":100,\"note\":\"   \"}", "REVIEW_NOTE_REQUIRED", 422);
    }

    @Test
    void optionalBlankNoteIsStoredAsNull() throws Exception {
        long id = submitted(cashierId, 100, 100, 0);
        mvc.perform(review(id, managerId, "MANAGER", "{\"note\":\"   \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewNote").value(org.hamcrest.Matchers.nullValue()));
        assertNull(jdbc.queryForObject("SELECT review_note FROM cashier_shifts WHERE id=?", String.class, id));
    }

    @Test
    void managerReviewNoteIsTrimmedBeforePersistence() throws Exception {
        long id = submitted(cashierId, 100, 120, 20);
        mvc.perform(review(id, managerId, "MANAGER", "{\"note\":\"  counted again  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewNote").value("counted again"));
        assertEquals("counted again", jdbc.queryForObject("SELECT review_note FROM cashier_shifts WHERE id=?", String.class, id));
    }

    @Test
    void negativeActualCashAndOverlongNoteAreRejectedAsBadRequest() throws Exception {
        long id = managerReview(cashierId, NOW);
        unchangedError(id, managerId, "MANAGER", "{\"actualCash\":-1,\"note\":\"x\"}", null, 400);
        long submitted = submitted(cashierId, 100, 100, 0);
        unchangedError(submitted, managerId, "MANAGER", "{\"note\":\"" + "x".repeat(256) + "\"}", null, 400);
    }

    @Test
    void openShiftCannotBeReviewed() throws Exception {
        long id = shifts.insertOpenShift(venueId, cashierId, LocalDate.of(2026, 11, 17), 0, NOW).id();
        unchangedError(id, managerId, "MANAGER", "{}", "SHIFT_NOT_REVIEWABLE", 409);
    }

    @Test
    void reconciledShiftCannotBeReviewedAgain() throws Exception {
        long id = reconciled(cashierId, managerId);
        unchangedError(id, adminId, "ADMIN", "{}", "SHIFT_NOT_REVIEWABLE", 409);
    }

    @Test
    void shiftOwnerCannotReviewTheirOwnShift() throws Exception {
        long id = submitted(managerId, 100, 100, 0);
        unchangedError(id, managerId, "MANAGER", "{}", null, 403);
    }

    @Test
    void adminCannotReviewOwnShift() throws Exception {
        long id = submitted(adminId, 100, 100, 0);
        unchangedError(id, adminId, "ADMIN", "{}", null, 403);
    }

    @Test
    void cashierRoleCannotReview() throws Exception {
        unchangedError(submittedId, otherCashierId, "CASHIER", "{}", null, 403);
    }

    @Test
    void unauthenticatedCallerCannotReview() throws Exception {
        String before = row(submittedId);
        long auditsBefore = auditCount(submittedId);
        mvc.perform(post(REVIEW + submittedId + "/review").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        assertEquals(before, row(submittedId));
        assertEquals(auditsBefore, auditCount(submittedId));
    }

    @Test
    void crossVenueAndUnknownShiftReturnNotFound() throws Exception {
        long foreign = submittedIn(otherVenueId, otherCashierId, 100, 100, 0);
        String before = row(foreign);
        unchangedError(foreign, managerId, "MANAGER", "{}", "Resource not found", 404);
        assertEquals(before, row(foreign));
        long auditsBeforeUnknown = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SHIFT_RECONCILED'", Long.class);
        mvc.perform(review(999999999L, managerId, "MANAGER", "{}"))
                .andExpect(status().isNotFound());
        assertEquals(auditsBeforeUnknown,
                jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SHIFT_RECONCILED'", Long.class));
    }

    @Test
    void arithmeticOverflowReturns422WithoutUpdatingOrAuditing() throws Exception {
        long id = managerReview(cashierId, NOW);
        jdbc.update("UPDATE cashier_shifts SET opening_float=? WHERE id=?", Long.MAX_VALUE, id);
        paidCash(id, 1);
        unchangedError(id, managerId, "MANAGER", "{\"actualCash\":0,\"note\":\"counted\"}", "SHIFT_CASH_TOTAL_OVERFLOW", 422);
        assertEquals(0, auditCount(id));
        assertNull(jdbc.queryForObject("SELECT expected_cash FROM cashier_shifts WHERE id=?", (rs, n) -> rs.getObject(1), id));
        assertNull(jdbc.queryForObject("SELECT actual_cash FROM cashier_shifts WHERE id=?", (rs, n) -> rs.getObject(1), id));
        assertNull(jdbc.queryForObject("SELECT variance FROM cashier_shifts WHERE id=?", (rs, n) -> rs.getObject(1), id));
    }

    private void unchangedError(long id, long userId, String role, String body, String detail, int statusCode) throws Exception {
        String before = row(id);
        long auditsBefore = auditCount(id);
        var result = mvc.perform(review(id, userId, role, body)).andExpect(status().is(statusCode));
        if (detail != null) result.andExpect(jsonPath("$.detail").value(detail));
        assertEquals(before, row(id));
        assertEquals(auditsBefore, auditCount(id));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder review(long id, long userId, String role, String body) {
        return post(REVIEW + id + "/review").header("Authorization", bearer(userId, role))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long submittedIn(long venue, long owner, long expected, long actual, long variance) {
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,version,
                    created_at,updated_at,submitted_at,expected_cash,actual_cash,variance,submit_note)
                VALUES (?,?,'2026-11-17','SUBMITTED',100,?,0,?,?,?,?,?,?, 'submitted note') RETURNING id
                """, Long.class, venue, owner, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), expected, actual, variance);
    }

    private long legacySubmitted(long owner) {
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,version,
                    created_at,updated_at,submitted_at)
                VALUES (?,?,'2026-11-17','SUBMITTED',0,?,0,?,?,?) RETURNING id
                """, Long.class, venueId, owner, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    private long managerReview(long owner, Instant forcedAt) {
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,
                    forced_review_at,version,created_at,updated_at)
                VALUES (?,?,'2026-11-16','MANAGER_REVIEW',100,?,?,0,?,?) RETURNING id
                """, Long.class, venueId, owner, java.sql.Timestamp.from(NOW),
                forcedAt == null ? null : java.sql.Timestamp.from(forcedAt), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    private long reconciled(long owner, long reviewer) {
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,version,
                    created_at,updated_at,submitted_at,expected_cash,actual_cash,variance,reviewed_by,reviewed_at)
                VALUES (?,?,'2026-11-17','RECONCILED',100,?,0,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, venueId, owner, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), 100L, 100L, 0L, reviewer, java.sql.Timestamp.from(NOW));
    }

    private void paidCash(long shiftId, long amount) {
        cashPayment(shiftId, "PAID", amount);
    }

    private void cashPayment(long shiftId, String status, long amount) {
        long owner = jdbc.queryForObject("SELECT cashier_id FROM cashier_shifts WHERE id=?", Long.class, shiftId);
        long orderId = jdbc.queryForObject("""
                INSERT INTO orders(venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,paid_total,
                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, '2026-11-17','OPEN',?,?,0,?,?,?,0) RETURNING id
                """, Long.class, venueId, "R" + UUID.randomUUID().toString().substring(0, 12), owner,
                amount, amount, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO payments(order_id,venue_id,business_date,shift_id,method,amount,status,paid_at,created_by,created_at,updated_at)
                VALUES (?,?,'2026-11-17',?,'CASH',?,?, ?,?,?,?)
                """, orderId, venueId, shiftId, amount, status, "PAID".equals(status) ? java.sql.Timestamp.from(NOW) : null, owner,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    private void qrisPaid(long owner, long amount) {
        long orderId = jdbc.queryForObject("""
                INSERT INTO orders(venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,paid_total,
                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, '2026-11-17','OPEN',?,?,0,?,?,?,0) RETURNING id
                """, Long.class, venueId, "Q" + UUID.randomUUID().toString().substring(0, 12), owner,
                amount, amount, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO payments(order_id,venue_id,business_date,shift_id,method,amount,status,provider,
                    provider_order_id,provider_idempotency_key,charge_state,charge_attempts,paid_at,created_by,created_at,updated_at)
                VALUES (?,?,'2026-11-17',NULL,'QRIS_MIDTRANS',?,'PAID','MIDTRANS',?,?,'NOT_SENT',0,?,?,?,?)
                """, orderId, venueId, amount, "NV-" + UUID.randomUUID(), UUID.randomUUID(),
                java.sql.Timestamp.from(NOW), owner, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    private long auditCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SHIFT_RECONCILED' AND entity_id=?", Long.class, id);
    }

    private String auditDetail(long id, String key) {
        return jdbc.queryForObject("SELECT details->>CAST(? AS text) FROM audit_logs WHERE action='SHIFT_RECONCILED' AND entity_id=?", String.class, key, id);
    }

    private String row(long id) {
        return jdbc.queryForObject("SELECT to_jsonb(s)::text FROM cashier_shifts s WHERE id=?", String.class, id);
    }

    private String value(long id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM cashier_shifts WHERE id=?", String.class, id);
    }

}
