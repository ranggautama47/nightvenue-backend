package com.nightvenue.api.shift;

import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.pos.OrderAuditWriter;
import com.nightvenue.api.pos.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

abstract class ShiftCutoffTestSupport {
    protected static final Instant DEFAULT_NOW = Instant.parse("2026-11-17T12:00:00Z");

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtEncoder jwtEncoder;
    @Autowired protected ShiftRepository shifts;
    @Autowired protected OrderRepository orders;
    @Autowired protected OrderAuditWriter audits;
    @Autowired protected BusinessDateService businessDates;
    @Autowired protected ShiftCutoffService cutoffService;
    @Autowired protected MutableTestClock testClock;

    protected String fixturePrefix;
    protected long venueJakarta;
    protected long venueJayapura;
    protected long cashier1;
    protected long cashier2;
    protected long cashier3;
    protected long managerId;

    @BeforeEach
    void setupFixtures() {
        testClock.set(DEFAULT_NOW);
        fixturePrefix = "sc" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        venueJakarta  = createVenue("vj",  "Venue Jakarta",  "Asia/Jakarta",  6);
        venueJayapura = createVenue("vp",  "Venue Jayapura", "Asia/Jayapura", 6);

        cashier1  = createUser("c1",  "CASHIER");
        cashier2  = createUser("c2",  "CASHIER");
        cashier3  = createUser("c3",  "CASHIER");
        managerId = createUser("mgr", "MANAGER");

        jdbc.update(
                "INSERT INTO user_venues(user_id, venue_id) VALUES (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)",
                cashier1, venueJakarta, cashier2, venueJakarta, cashier3, venueJakarta,
                managerId, venueJakarta, cashier1, venueJayapura);
    }

    @AfterEach
    void cleanupFixtures() {
        testClock.set(DEFAULT_NOW);
        if (fixturePrefix == null) return;

        // FK order: payments -> orders -> cashier_shifts -> audit_logs -> user_venues -> users -> venues
        jdbc.update("""
                DELETE FROM payments
                WHERE created_by IN (SELECT id FROM users WHERE email LIKE ?)
                   OR venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixturePrefix + "%", fixturePrefix + "%");

        jdbc.update("""
                DELETE FROM orders
                WHERE cashier_id IN (SELECT id FROM users WHERE email LIKE ?)
                   OR venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixturePrefix + "%", fixturePrefix + "%");

        jdbc.update("""
                DELETE FROM cashier_shifts
                WHERE cashier_id IN (SELECT id FROM users WHERE email LIKE ?)
                   OR venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixturePrefix + "%", fixturePrefix + "%");

        jdbc.update("""
                DELETE FROM audit_logs
                WHERE actor_user_id IN (SELECT id FROM users WHERE email LIKE ?)
                   OR venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixturePrefix + "%", fixturePrefix + "%");

        jdbc.update("""
                DELETE FROM user_venues
                WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)
                   OR venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixturePrefix + "%", fixturePrefix + "%");

        jdbc.update("DELETE FROM users WHERE email LIKE ?", fixturePrefix + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", fixturePrefix + "%");
    }

    protected long createVenue(String suffix, String name, String timezone, int cutoffHour) {
        String code = fixturePrefix + "-" + suffix;
        return jdbc.queryForObject("""
                INSERT INTO venues(code, name, timezone, business_day_cutoff_hour, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, now(), now()) RETURNING id
                """, Long.class, code, name, timezone, cutoffHour);
    }

    protected long createUser(String suffix, String role) {
        String email = (fixturePrefix + "-" + suffix + "@test.local").toLowerCase();
        return jdbc.queryForObject("""
                INSERT INTO users(email, password_hash, full_name, role, is_active, version, created_at, updated_at)
                VALUES (?, 'hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, email, fixturePrefix + " " + suffix, role);
    }

    protected long insertOpenShift(long venueId, long cashierId, LocalDate businessDate) {
        Timestamp openedAt = Timestamp.from(testClock.instant());
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id, cashier_id, business_date, status, opening_float, opened_at,
                                           version, created_at, updated_at)
                VALUES (?, ?, ?, 'OPEN', 100, ?, 0, ?, ?) RETURNING id
                """, Long.class, venueId, cashierId, Date.valueOf(businessDate), openedAt, openedAt, openedAt);
    }

    protected long insertShiftWithStatus(long venueId, long cashierId, LocalDate businessDate, String status) {
        Timestamp now = Timestamp.from(testClock.instant());
        return switch (status) {
            case "SUBMITTED" -> jdbc.queryForObject("""
                    INSERT INTO cashier_shifts(venue_id, cashier_id, business_date, status, opening_float, opened_at,
                                               version, created_at, updated_at, submitted_at,
                                               expected_cash, actual_cash, variance, submit_note)
                    VALUES (?, ?, ?, 'SUBMITTED', 100, ?, 0, ?, ?, ?, 500, 500, 0, 'submitted') RETURNING id
                    """, Long.class, venueId, cashierId, Date.valueOf(businessDate), now, now, now, now);
            case "MANAGER_REVIEW" -> jdbc.queryForObject("""
                    INSERT INTO cashier_shifts(venue_id, cashier_id, business_date, status, opening_float, opened_at,
                                               version, created_at, updated_at, forced_review_at)
                    VALUES (?, ?, ?, 'MANAGER_REVIEW', 100, ?, 0, ?, ?, ?) RETURNING id
                    """, Long.class, venueId, cashierId, Date.valueOf(businessDate), now, now, now, now);
            case "RECONCILED" -> jdbc.queryForObject("""
                    INSERT INTO cashier_shifts(venue_id, cashier_id, business_date, status, opening_float, opened_at,
                                               version, created_at, updated_at, submitted_at,
                                               expected_cash, actual_cash, variance,
                                               reviewed_by, reviewed_at, review_note)
                    VALUES (?, ?, ?, 'RECONCILED', 100, ?, 0, ?, ?, ?, 500, 500, 0, ?, ?, 'reconciled note') RETURNING id
                    """, Long.class, venueId, cashierId, Date.valueOf(businessDate), now, now, now, now, managerId, now);
            default -> throw new IllegalArgumentException("Unsupported status: " + status);
        };
    }

    protected String bearer(long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("nightvenue")
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("role", role)
                .build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CutoffClockConfig {
        @Bean @Primary MutableTestClock shiftCutoffClock() { return new MutableTestClock(DEFAULT_NOW); }
    }

    static final class MutableTestClock extends Clock {
        private Instant instant;
        MutableTestClock(Instant instant) { this.instant = instant; }
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
