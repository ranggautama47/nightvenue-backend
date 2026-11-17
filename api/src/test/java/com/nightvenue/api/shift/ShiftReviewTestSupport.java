package com.nightvenue.api.shift;

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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

abstract class ShiftReviewTestSupport {
    protected static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtEncoder jwtEncoder;
    @Autowired protected ShiftRepository shifts;
    @Autowired protected TestClock clock;

    protected String fixture;
    protected long venueId, otherVenueId, cashierId, otherCashierId, managerId, adminId, submittedId;

    @BeforeEach
    void setupReviewFixtures() {
        clock.set(NOW);
        fixture = "SR" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        cashierId = user("CASHIER", "-c");
        otherCashierId = user("CASHIER", "-o");
        managerId = user("MANAGER", "-m");
        adminId = user("ADMIN", "-a");
        venueId = venue(fixture);
        otherVenueId = venue(fixture + "B");
        jdbc.update("INSERT INTO user_venues(user_id,venue_id) VALUES (?,?),(?,?),(?,?)",
                cashierId, venueId, managerId, venueId, adminId, venueId);
        submittedId = submitted(cashierId, 700, 800, 100);
    }

    @AfterEach
    void cleanupReviewFixtures() {
        if (fixture == null) return;
        jdbc.update("DELETE FROM payments WHERE created_by IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM orders WHERE cashier_id IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM cashier_shifts WHERE venue_id IN (?,?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN (?,?) OR actor_user_id IN (?,?,?,?)",
                venueId, otherVenueId, cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM user_venues WHERE user_id IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM users WHERE id IN (?,?,?,?)", cashierId, otherCashierId, managerId, adminId);
        jdbc.update("DELETE FROM venues WHERE id IN (?,?)", venueId, otherVenueId);
    }

    protected long submitted(long owner, long expected, long actual, long variance) {
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts(venue_id,cashier_id,business_date,status,opening_float,opened_at,version,
                    created_at,updated_at,submitted_at,expected_cash,actual_cash,variance,submit_note)
                VALUES (?,?,'2026-11-17','SUBMITTED',100,?,0,?,?,?,?,?,?, 'submitted note') RETURNING id
                """, Long.class, venueId, owner, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), expected, actual, variance);
    }

    protected long user(String role, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO users(email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, (fixture + suffix + "@test.local").toLowerCase(), fixture + suffix, role);
    }

    protected long venue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues(code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?,?,'Asia/Jakarta',6,true,now(),now()) RETURNING id
                """, Long.class, code, code);
    }

    protected String bearer(long id, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(id))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean @Primary TestClock shiftReviewClock() { return new TestClock(NOW); }
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
