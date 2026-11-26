package com.nightvenue.api.chat;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.seed.DemoDataSeeder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ChatVenueTimeRepositoryIntegrationTests {

    private static final String FIXTURE_PREFIX = "VTFIX-";

    @Autowired ChatVenueTimeRepository venueTimeRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM user_venues WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venue_tables WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", FIXTURE_PREFIX + "%");
    }

    @Test
    void activeVenueWithJakartaTimezoneAndCutoff6ReturnsContext() {
        long venueId = insertVenue(fixtureCode(), true, "Asia/Jakarta", 6);
        Optional<VenueTimeContext> result = venueTimeRepository.findActiveVenueTime(venueId);
        assertTrue(result.isPresent(), "Active venue must return context");
        assertEquals(ZoneId.of("Asia/Jakarta"), result.get().zone());
        assertEquals(6, result.get().cutoffHour());
    }

    @Test
    void inactiveVenueReturnsEmpty() {
        long venueId = insertVenue(fixtureCode(), false, "Asia/Jakarta", 6);
        Optional<VenueTimeContext> result = venueTimeRepository.findActiveVenueTime(venueId);
        assertTrue(result.isEmpty(), "Inactive venue must return empty");
    }

    @Test
    void unknownVenueIdReturnsEmpty() {
        Optional<VenueTimeContext> result = venueTimeRepository.findActiveVenueTime(999_999_999L);
        assertTrue(result.isEmpty(), "Unknown venue id must return empty");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private long insertVenue(String code, boolean active, String timezone, int cutoffHour) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, address, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, void_manager_approval_threshold, opening_hours, is_active, created_at, updated_at)
                VALUES (?, ?, 'Addr', ?, ?, 0, 0, 500000, '{}'::jsonb, ?, ?, ?)
                RETURNING id
                """, Long.class, code, "Venue " + code, timezone, cutoffHour, active, now, now);
    }

    private String fixtureCode() {
        return FIXTURE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
