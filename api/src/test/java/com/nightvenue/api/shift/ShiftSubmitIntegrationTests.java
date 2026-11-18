package com.nightvenue.api.shift;

import com.nightvenue.api.TestcontainersConfig;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ShiftSubmitIntegrationTests.TestClockConfig.class})
class ShiftSubmitIntegrationTests {
    private static final String SHIFTS = "/api/v1/shifts/";
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ShiftRepository shifts;
    @Autowired TestClock clock;
    private String fixture;
    private long venueId, otherVenueId, cashierId, otherCashierId, managerId, adminId;
    private long shiftId;

    @BeforeEach
    void setup() {
        clock.set(NOW);
        fixture = "SS" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        cashierId = user("CASHIER", "-c");
        otherCashierId = user("CASHIER", "-o");
        managerId = user("MANAGER", "-m");
        adminId = user("ADMIN", "-a");
        venueId = venue(fixture);
        otherVenueId = venue(fixture + "B");
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?),(?,?),(?,?),(?,?)",
                cashierId, venueId, otherCashierId, venueId, managerId, venueId, adminId, venueId);
        shiftId = shifts.insertOpenShift(venueId, cashierId, LocalDate.of(2026, 11, 17), 100L, NOW).id();
    }

    @AfterEach
    void cleanup() {
        if (fixture == null) return;
        jdbc.update("DELETE FROM payments WHERE created_by IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM orders WHERE cashier_id IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN (?,?) OR actor_user_id IN (?,?,?,?)",
                venueId, otherVenueId, cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM cashier_shifts WHERE cashier_id IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM user_venues WHERE user_id IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM users WHERE id IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM venues WHERE id IN (?,?)", venueId, otherVenueId);
    }

    @Test
    void submitsExpectedCashAndWritesAudit() throws Exception {
        payment(shiftId, "CASH", "PAID", 250L);
        payment(shiftId, "CASH", "PAID", 350L);
        mvc.perform(submit(shiftId, cashierId, "CASHIER", "{\"actualCash\":800,\"note\":\"  count done  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.openingFloat").value(100)).andExpect(jsonPath("$.expectedCash").value(700))
                .andExpect(jsonPath("$.actualCash").value(800)).andExpect(jsonPath("$.variance").value(100))
                .andExpect(jsonPath("$.submitNote").value("count done"));
        assertEquals("SUBMITTED", jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id=?", String.class, shiftId));
        assertEquals(700L, jdbc.queryForObject("SELECT expected_cash FROM cashier_shifts WHERE id=?", Long.class, shiftId));
        assertEquals(800L, jdbc.queryForObject("SELECT actual_cash FROM cashier_shifts WHERE id=?", Long.class, shiftId));
        assertEquals(100L, jdbc.queryForObject("SELECT variance FROM cashier_shifts WHERE id=?", Long.class, shiftId));
        assertEquals(NOW, jdbc.queryForObject("SELECT submitted_at FROM cashier_shifts WHERE id=?", (rs, n) -> rs.getTimestamp(1).toInstant(), shiftId));
        assertEquals("count done", jdbc.queryForObject("SELECT submit_note FROM cashier_shifts WHERE id=?", String.class, shiftId));
        assertEquals(1, auditCount("SHIFT_SUBMITTED", shiftId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id=? AND action='SHIFT_SUBMITTED' AND jsonb_exists(details, 'note')", Integer.class, shiftId));
        assertEquals("700", auditDetail(shiftId, "expectedCash"));
        assertEquals("800", auditDetail(shiftId, "actualCash"));
        assertEquals("100", auditDetail(shiftId, "variance"));
        assertFalse(jdbc.queryForObject("SELECT details::text FROM audit_logs WHERE entity_id=? AND action='SHIFT_SUBMITTED'", String.class, shiftId).contains("count done"));
    }

    @Test
    void whitespaceOnlyNotePersistsAsNull() throws Exception {
        long id = shifts.insertOpenShift(venueId, otherCashierId, LocalDate.of(2026, 11, 17), 0, NOW).id();
        mvc.perform(submit(id, otherCashierId, "CASHIER", "{\"actualCash\":0,\"note\":\"   \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.submitNote").value(nullValue()));
        assertNull(jdbc.queryForObject("SELECT submit_note FROM cashier_shifts WHERE id=?", String.class, id));
    }

    @Test
    void sumsOnlyCashPaidOnThisShiftAndSupportsAllVarianceSigns() throws Exception {
        payment(shiftId, "CASH", "PAID", 20L);
        payment(shiftId, "QRIS_MIDTRANS", "PAID", 900L);
        payment(shiftId, "CASH", "PENDING", 80L);
        payment(shiftId, "CASH", "FAILED", 81L);
        payment(shiftId, "CASH", "CANCELLED", 82L);
        long anotherShift = jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,forced_review_at,version,created_at,updated_at)
                VALUES (?,?,'2026-11-16','MANAGER_REVIEW',0,?,?,0,?,?) RETURNING id
                """, Long.class, venueId, otherCashierId, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        payment(anotherShift, "CASH", "PAID", 500L);
        mvc.perform(submit(shiftId, cashierId, "CASHIER", "{\"actualCash\":120}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(120))
                .andExpect(jsonPath("$.variance").value(0));
        for (long actual : new long[]{0, 100, 150}) {
            long id = shifts.insertOpenShift(venueId, otherCashierId, LocalDate.of(2026, 11, 17), 100, NOW).id();
            // This cashier owns only one OPEN shift at a time; close it before preparing the next variance case.
            mvc.perform(submit(id, otherCashierId, "CASHIER", "{\"actualCash\":" + actual + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(100))
                    .andExpect(jsonPath("$.variance").value(actual - 100));
        }
    }

    @Test
    void nonOpenStatusesConflictAndSuccessfulSubmitClosesCashPaymentPath() throws Exception {
        long submitted = jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,version,created_at,updated_at,
                  submitted_at,expected_cash,actual_cash,variance)
                VALUES (?,?,'2026-11-17','SUBMITTED',0,?,0,?,?,?,0,0,0) RETURNING id
                """, Long.class, venueId, otherCashierId, java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        long review = jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,forced_review_at,version,created_at,updated_at)
                VALUES (?,?,'2026-11-17','MANAGER_REVIEW',0,?,?,0,?,?) RETURNING id
                """, Long.class, venueId, adminId, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        long reconciled = jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,version,created_at,updated_at,
                  submitted_at,expected_cash,actual_cash,variance,reviewed_by,reviewed_at)
                VALUES (?,?,'2026-11-17','RECONCILED',0,?,0,?,?,?,0,0,0,?,?) RETURNING id
                """, Long.class, venueId, managerId, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), adminId, java.sql.Timestamp.from(NOW));
        for (long id : new long[]{submitted, review, reconciled})
            {
                String before = shiftRow(id);
                mvc.perform(submit(id, jdbc.queryForObject("SELECT cashier_id FROM cashier_shifts WHERE id=?", Long.class, id),
                            id == submitted ? "CASHIER" : id == review ? "ADMIN" : "MANAGER", "{\"actualCash\":0}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("SHIFT_NOT_OPEN"));
                assertEquals(before, shiftRow(id));
            }

        mvc.perform(submit(shiftId, cashierId, "CASHIER", "{\"actualCash\":0}" )).andExpect(status().isOk());
        String submittedBefore = shiftRow(shiftId);
        long orderId = insertOrder(venueId, cashierId);
        mvc.perform(post("/api/v1/orders/" + orderId + "/payments")
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("SHIFT_NOT_OPEN"));
        assertEquals(submittedBefore, shiftRow(shiftId));
    }

    @Test
    void rejectsInvalidPayloadAndUnauthorizedCallersWithoutChangingShift() throws Exception {
        for (String body : new String[]{"{\"actualCash\":-1}", "{}", "{\"actualCash\":0,\"note\":\"" + "x".repeat(256) + "\"}"}) {
            String before = shiftRow(shiftId);
            mvc.perform(submit(shiftId, cashierId, "CASHIER", body)).andExpect(status().isBadRequest());
            assertEquals(before, shiftRow(shiftId));
        }
        for (long id : new long[]{otherCashierId, managerId, adminId}) {
            String before = shiftRow(shiftId);
            mvc.perform(submit(shiftId, id, role(id), "{\"actualCash\":0}")).andExpect(status().isForbidden());
            assertEquals(before, shiftRow(shiftId));
        }
        String before = shiftRow(shiftId);
        mvc.perform(post(SHIFTS + shiftId + "/submit").contentType(MediaType.APPLICATION_JSON).content("{\"actualCash\":0}"))
                .andExpect(status().isUnauthorized());
        assertEquals(before, shiftRow(shiftId));
        before = shiftRow(shiftId);
        mvc.perform(submit(999999999L, cashierId, "CASHIER", "{\"actualCash\":0}" )).andExpect(status().isNotFound());
        assertEquals(before, shiftRow(shiftId));
    }

    @Test
    void venueConcealmentStaleAndOverflowBehaveAsSpecified() throws Exception {
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, otherVenueId);
        mvc.perform(submit(shiftId, cashierId, "CASHIER", "{\"actualCash\":0}" )).andExpect(status().isOk());
        long foreign = shifts.insertOpenShift(otherVenueId, otherCashierId, LocalDate.of(2026, 11, 17), 0, NOW).id();
        String foreignBefore = shiftRow(foreign);
        mvc.perform(submit(foreign, managerId, "MANAGER", "{\"actualCash\":0}" )).andExpect(status().isNotFound());
        assertEquals(foreignBefore, shiftRow(foreign));

        long stale = jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,version,created_at,updated_at)
                VALUES (?,?,'2026-11-16','OPEN',0,?,0,?,?) RETURNING id
                """, Long.class, venueId, cashierId, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        mvc.perform(submit(stale, cashierId, "CASHIER", "{\"actualCash\":0}" )).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("SHIFT_NOT_OPEN"));
        assertEquals("MANAGER_REVIEW", jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id=?", String.class, stale));
        assertNotNull(jdbc.queryForObject("SELECT forced_review_at FROM cashier_shifts WHERE id=?", java.sql.Timestamp.class, stale));
        assertEquals(1, auditCount("SHIFT_FORCED_REVIEW", stale));

        long overflow = shifts.insertOpenShift(venueId, managerId, LocalDate.of(2026, 11, 17), Long.MAX_VALUE - 1, NOW).id();
        payment(overflow, "CASH", "PAID", 2L);
        String overflowBefore = shiftRow(overflow);
        mvc.perform(submit(overflow, managerId, "MANAGER", "{\"actualCash\":0}" )).andExpect(status().isUnprocessableContent());
        assertEquals(overflowBefore, shiftRow(overflow));
        assertEquals(0, auditCount("SHIFT_SUBMITTED", overflow));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(long id, long user, String role, String body) {
        return post(SHIFTS + id + "/submit").header("Authorization", bearer(user, role))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private int auditCount(String action, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action=? AND entity_id=?", Integer.class, action, id);
    }
    private String auditDetail(long id, String key) {
        return jdbc.queryForObject("SELECT details->>CAST(? AS text) FROM audit_logs WHERE entity_id=? AND action='SHIFT_SUBMITTED'", String.class, key, id);
    }
    private String shiftRow(long id) {
        return jdbc.queryForObject("SELECT to_jsonb(s)::text FROM cashier_shifts s WHERE id=?", String.class, id);
    }
    private void payment(long shift, String method, String status, long amount) {
        long owner = jdbc.queryForObject("SELECT cashier_id FROM cashier_shifts WHERE id=?", Long.class, shift);
        long venue = jdbc.queryForObject("SELECT venue_id FROM cashier_shifts WHERE id=?", Long.class, shift);
        long order = insertOrder(venue, owner);
        java.sql.Timestamp paidAt = "PAID".equals(status) ? java.sql.Timestamp.from(NOW) : null;
        java.sql.Timestamp createdAt = java.sql.Timestamp.from(NOW);
        if ("CASH".equals(method)) {
            jdbc.update("""
                    INSERT INTO payments(order_id,venue_id,business_date,shift_id,method,amount,status,paid_at,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,'CASH',?,?,?,?,?,?)
                    """, order, venue, java.sql.Date.valueOf(LocalDate.of(2026, 11, 17)), shift,
                    amount, status, paidAt, owner, createdAt, createdAt);
        } else if ("QRIS_MIDTRANS".equals(method)) {
            jdbc.update("""
                    INSERT INTO payments(order_id,venue_id,business_date,shift_id,method,amount,status,provider,
                      provider_order_id,provider_idempotency_key,charge_state,charge_attempts,paid_at,created_by,created_at,updated_at)
                    VALUES (?,?,?,NULL,'QRIS_MIDTRANS',?,?,'MIDTRANS',?,?, 'NOT_SENT',0,?,?,?,?)
                    """, order, venue, java.sql.Date.valueOf(LocalDate.of(2026, 11, 17)), amount, status,
                    "NV-" + UUID.randomUUID(), UUID.randomUUID(), paidAt, owner, createdAt, createdAt);
        } else {
            throw new IllegalArgumentException("Unsupported test payment method: " + method);
        }
    }
    private long insertOrder(long venue, long owner) {
        return jdbc.queryForObject("""
                INSERT INTO orders(venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,paid_total,opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, '2026-11-17','OPEN',100,100,0,?,?,?,0) RETURNING id
                """, Long.class, venue, "O" + UUID.randomUUID().toString().substring(0, 12), owner,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }
    private long user(String role, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO users(email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, (fixture + suffix + "@test.local").toLowerCase(), fixture + suffix, role);
    }
    private long venue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues(code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?,?,'Asia/Jakarta',6,true,now(),now()) RETURNING id
                """, Long.class, code, code);
    }
    private String role(long id) { return id == managerId ? "MANAGER" : id == adminId ? "ADMIN" : "CASHIER"; }
    private String bearer(long id, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(id))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean @Primary TestClock shiftSubmitClock() { return new TestClock(NOW); }
    }
    static final class TestClock extends Clock {
        private Instant instant;
        TestClock(Instant instant) { this.instant = instant; }
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
