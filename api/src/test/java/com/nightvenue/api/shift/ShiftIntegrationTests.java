package com.nightvenue.api.shift;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.businessday.BusinessDateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ShiftIntegrationTests {
    private static final String SHIFTS = "/api/v1/shifts";
    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Jakarta");
    private static final int CUTOFF = 6;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ObjectMapper mapper;
    @Autowired ShiftRepository shiftRepository;
    @Autowired BusinessDateService businessDates;

    private String fixture;
    private long venueId, venueBId, cashierId, managerId, adminId, customerId, outsiderId;
    private final List<UUID> keys = new ArrayList<>();

    @BeforeEach
    void setup() {
        fixture = "SH-" + UUID.randomUUID().toString().substring(0, 8);
        cashierId = createUser("CASHIER", "-c");
        managerId = createUser("MANAGER", "-m");
        adminId = createUser("ADMIN", "-a");
        outsiderId = createUser("CASHIER", "-o");
        customerId = createUser("CUSTOMER", "-x");
        venueId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, fixture, fixture);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueId);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", managerId, venueId);
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", adminId, venueId);
        venueBId = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, fixture + "-B", fixture + "-B");
        // OD-2 stale-shift tests open shifts across venues; the access model
        // (AuthUserRepository.activeVenueIds) grants CASHIER/MANAGER access to every
        // row in user_venues, so the cashier legitimately works at both venues.
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?)", cashierId, venueBId);
    }

    @AfterEach
    void cleanup() {
        if (fixture == null) return;
        keys.forEach(key -> jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key));
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN (?, ?) OR actor_user_id IN (?, ?, ?, ?, ?)",
                venueId, venueBId, cashierId, managerId, adminId, outsiderId, customerId);
        jdbc.update("DELETE FROM cashier_shifts WHERE venue_id IN (?, ?)", venueId, venueBId);
        jdbc.update("DELETE FROM user_venues WHERE user_id IN (?, ?, ?, ?)", cashierId, managerId, adminId, outsiderId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?, ?)", cashierId, managerId, adminId, outsiderId, customerId);
        jdbc.update("DELETE FROM venues WHERE id IN (?, ?)", venueId, venueBId);
    }

    @Test
    void cashierOpensOwnShift_201_andAudited() throws Exception {
        MvcResult result = mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":" + venueId + ",\"openingFloat\":50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        var doc = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(venueId, doc.path("venueId").asLong());
        assertEquals(cashierId, doc.path("cashierId").asLong());
        assertEquals(50000, doc.path("openingFloat").asLong());
        assertEquals(businessDate().toString(), doc.path("businessDate").asText());
        long shiftId = doc.path("id").asLong();
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id=?", String.class, shiftId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE entity_type='CASHIER_SHIFT' AND entity_id=? AND action='SHIFT_OPENED'
                """, Integer.class, shiftId));
    }

    @Test
    void managerAndAdminEachOpenTheirOwnShift() throws Exception {
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(managerId, "MANAGER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":0}"))
                .andExpect(status().isCreated());
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(adminId, "ADMIN")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":0}"))
                .andExpect(status().isCreated());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cashier_shifts WHERE cashier_id=? AND status='OPEN'", Integer.class, managerId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cashier_shifts WHERE cashier_id=? AND status='OPEN'", Integer.class, adminId));
    }

    @Test
    void customerRoleForbidden() throws Exception {
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(customerId, "CUSTOMER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":0}"))
                .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cashier_shifts WHERE venue_id IN (?, ?)", Integer.class, venueId, venueBId));
    }

    @Test
    void callerCannotControlCashierId() throws Exception {
        MvcResult result = mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":" + venueId + ",\"openingFloat\":0,\"cashierId\":" + managerId + "}")
                )
                .andExpect(status().isCreated()).andReturn();
        assertEquals(cashierId, mapper.readTree(result.getResponse().getContentAsString()).path("cashierId").asLong());
    }

    @Test
    void duplicateOpenOnSameBusinessDate_409() throws Exception {
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":0}"))
                .andExpect(status().isCreated());
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":1000}"))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cashier_shifts WHERE cashier_id=?", Integer.class, cashierId));
    }

    @Test
    void staleOpenMovesToManagerReview_withForcedReviewAudit_thenOpensAtomically() throws Exception {
        long staleId = shiftRepository.insertOpenShift(venueId, cashierId, businessDate().minusDays(1), 0L, Instant.now()).id();
        MvcResult result = mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":100}"))
                .andExpect(status().isCreated()).andReturn();
        long newId = mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        assertNotEquals(staleId, newId);
        assertEquals("MANAGER_REVIEW", jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id=?", String.class, staleId));
        assertNotNull(jdbc.queryForObject("SELECT forced_review_at FROM cashier_shifts WHERE id=?", java.sql.Timestamp.class, staleId));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id=?", String.class, newId));
        assertEquals(businessDate().toString(), jdbc.queryForObject(
                "SELECT business_date::text FROM cashier_shifts WHERE id=?", String.class, newId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SHIFT_FORCED_REVIEW' AND entity_id=? AND venue_id=?",
                Integer.class, staleId, venueId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SHIFT_OPENED' AND entity_id=?",
                Integer.class, newId));
    }

    @Test
    void staleShiftInAnotherVenueResolvedAgainstItsOwnVenue() throws Exception {
        // shift opened in venue A, current by A's business date -> opening in B must NOT flip it, 409 instead
        long currentInA = shiftRepository.insertOpenShift(venueId, cashierId, businessDate(), 0L, Instant.now()).id();
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueBId + ",\"openingFloat\":0}"))
                .andExpect(status().isConflict());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id=?", String.class, currentInA));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SHIFT_FORCED_REVIEW' AND entity_id=?",
                Integer.class, currentInA), "a still-current shift must never be flipped from another venue's request");
    }

    @Test
    void staleShiftOfAnotherVenueFlipsByItsOwnVenueThenNewShiftOpens() throws Exception {
        // stale relative to venue B's own business date while the cashier opens in venue A
        long staleId = shiftRepository.insertOpenShift(venueBId, cashierId, businessDate().minusDays(1), 0L, Instant.now()).id();
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":0}"))
                .andExpect(status().isCreated());
        assertEquals("MANAGER_REVIEW", jdbc.queryForObject("SELECT status FROM cashier_shifts WHERE id=?", String.class, staleId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SHIFT_FORCED_REVIEW' AND entity_id=? AND venue_id=?",
                Integer.class, staleId, venueBId), "forced review audit is written against the old shift's venue");
    }

    @Test
    void parallelOpenExactlyOneSucceeds() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> a = pool.submit(() -> {
            start.await();
            return mvc.perform(post(SHIFTS)
                            .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":0}"))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> b = pool.submit(() -> {
            start.await();
            return mvc.perform(post(SHIFTS)
                            .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":100}"))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        try {
            List<Integer> statuses = new java.util.ArrayList<>(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(201, 409), statuses);
        } finally {
            pool.shutdown();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cashier_shifts WHERE cashier_id=?", Integer.class, cashierId));
    }

    @Test
    void unauthorizedVenueReturns404() throws Exception {
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(outsiderId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":0}"))
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cashier_shifts WHERE cashier_id=?", Integer.class, outsiderId));
    }

    @Test
    void openingFloatValidation() throws Exception {
        mvc.perform(post(SHIFTS)
                        .header("Authorization", bearer(cashierId, "CASHIER")).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"venueId\":" + venueId + ",\"openingFloat\":-1}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cashier_shifts WHERE venue_id IN (?, ?)", Integer.class, venueId, venueBId));
    }

    private LocalDate businessDate() {
        return businessDates.currentBusinessDate(VENUE_ZONE, CUTOFF);
    }

    private long createUser(String role, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, fixture.toLowerCase() + suffix + "@test.local", fixture + suffix, role);
    }

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
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
