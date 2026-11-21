package com.nightvenue.api.chat;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.seed.DemoDataSeeder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ChatRepositoryIntegrationTests {

    private static final String FIXTURE_PREFIX = "CHATFIX-";

    @Autowired ChatRepository chatRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoDataSeeder seeder;
    @Autowired Clock clock;
    @Autowired BusinessDateService businessDates;

    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM chat_route_logs WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM promotions WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venue_faqs WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM user_venues WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venue_tables WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", FIXTURE_PREFIX + "%");
    }

    @Test
    void publicVenueLookupExcludesInactiveAndUnknown() {
        long activeId = jdbc.queryForObject("SELECT id FROM venues WHERE is_active = true ORDER BY id LIMIT 1", Long.class);
        Optional<PublicVenueProfile> active = chatRepository.findActivePublicVenue(activeId);
        assertTrue(active.isPresent());
        assertNotNull(active.get().name());

        long inactiveId = insertVenue(trueCode(), false);
        assertTrue(chatRepository.findActivePublicVenue(inactiveId).isEmpty());
        assertTrue(chatRepository.findActivePublicVenue(999_999_999L).isEmpty());
    }

    @Test
    void promotionsAreFilteredByDateWindow() {
        long venueId = insertVenue(trueCode(), true);
        LocalDate day = LocalDate.of(2026, 11, 15);
        jdbc.update("""
                INSERT INTO promotions (venue_id, title, description, starts_on, ends_on, is_active)
                VALUES (?, 'Inside', 'd', ?, ?, true)
                """, venueId, day.minusDays(1), day.plusDays(1));
        jdbc.update("""
                INSERT INTO promotions (venue_id, title, description, starts_on, ends_on, is_active)
                VALUES (?, 'Before Start', 'd', ?, ?, true)
                """, venueId, day.plusDays(1), day.plusDays(10));
        jdbc.update("""
                INSERT INTO promotions (venue_id, title, description, starts_on, ends_on, is_active)
                VALUES (?, 'After End', 'd', ?, ?, true)
                """, venueId, day.minusDays(10), day.minusDays(1));

        List<PromotionWindow> active = chatRepository.findActivePromotions(venueId, day);
        assertEquals(1, active.size());
        assertEquals("Inside", active.get(0).title());
        assertEquals(day.minusDays(1), active.get(0).startsOn());
        assertEquals(day.plusDays(1), active.get(0).endsOn());

        List<String> titlesMinus1 = chatRepository.findActivePromotions(venueId, day.minusDays(1)).stream()
                .map(PromotionWindow::title).toList();
        assertTrue(titlesMinus1.contains("Inside"));
        assertTrue(titlesMinus1.contains("After End"));
        assertFalse(titlesMinus1.contains("Before Start"));

        List<String> titlesPlus1 = chatRepository.findActivePromotions(venueId, day.plusDays(1)).stream()
                .map(PromotionWindow::title).toList();
        assertTrue(titlesPlus1.contains("Inside"));
        assertTrue(titlesPlus1.contains("Before Start"));
        assertFalse(titlesPlus1.contains("After End"));
        assertTrue(chatRepository.findActivePromotions(venueId, day.minusDays(2)).stream().noneMatch(p -> p.title().equals("Inside")));
        assertTrue(chatRepository.findActivePromotions(venueId, day.plusDays(2)).stream().noneMatch(p -> p.title().equals("Inside")));
    }

    @Test
    void chatRouteLogInsertRoundTripsBigDecimalAndNulls() {
        long venueId = insertVenue(trueCode(), true);
        Instant createdAt = Instant.parse("2026-11-13T12:00:00Z");
        chatRepository.insertChatRouteLog(new ChatRouteLogEntry(
                venueId, "LLM", "GENERAL_VENUE_INFORMATION",
                new BigDecimal("0.9876"), true, 12, "GEMINI", 34,
                null, null, new BigDecimal("0.000123"), createdAt));

        Long insertedId = jdbc.queryForObject("""
                SELECT id FROM chat_route_logs
                WHERE venue_id = ? AND route = 'LLM' AND intent = 'GENERAL_VENUE_INFORMATION' AND created_at = ?
                ORDER BY id DESC LIMIT 1
                """, Long.class, venueId, Timestamp.from(createdAt));
        assertNotNull(insertedId);

        BigDecimal confidence = jdbc.queryForObject("SELECT confidence FROM chat_route_logs WHERE id = ?", BigDecimal.class, insertedId);
        BigDecimal cost = jdbc.queryForObject("SELECT estimated_cost_usd FROM chat_route_logs WHERE id = ?", BigDecimal.class, insertedId);
        assertEquals(0, new BigDecimal("0.9876").compareTo(confidence));
        assertEquals(0, new BigDecimal("0.000123").compareTo(cost));
        assertNull(jdbc.queryForObject("SELECT input_tokens FROM chat_route_logs WHERE id = ?", Integer.class, insertedId));
        assertNull(jdbc.queryForObject("SELECT output_tokens FROM chat_route_logs WHERE id = ?", Integer.class, insertedId));

        Instant nullTelemetryCreatedAt = createdAt.plusSeconds(1);
        chatRepository.insertChatRouteLog(new ChatRouteLogEntry(
                venueId, "LLM", "GENERAL_VENUE_INFORMATION",
                new BigDecimal("0.9876"), true, null, "GEMINI", null,
                null, null, null, nullTelemetryCreatedAt));

        Long nullTelemetryId = jdbc.queryForObject("""
                SELECT id FROM chat_route_logs
                WHERE venue_id = ? AND route = 'LLM' AND intent = 'GENERAL_VENUE_INFORMATION' AND created_at = ?
                ORDER BY id DESC LIMIT 1
                """, Long.class, venueId, Timestamp.from(nullTelemetryCreatedAt));
        assertNotNull(nullTelemetryId);
        assertNull(jdbc.queryForObject("SELECT input_tokens FROM chat_route_logs WHERE id = ?", Integer.class, nullTelemetryId));
        assertNull(jdbc.queryForObject("SELECT output_tokens FROM chat_route_logs WHERE id = ?", Integer.class, nullTelemetryId));
        assertNull(jdbc.queryForObject("SELECT estimated_cost_usd FROM chat_route_logs WHERE id = ?", BigDecimal.class, nullTelemetryId));
    }

    @Test
    void seederIdempotencyKeepsFaqAndPromotionCountsUnchanged() {
        List<Long> demoVenueIds = jdbc.queryForList("SELECT id FROM venues WHERE code IN ('NV-JKT-01','NV-JKT-02') ORDER BY id", Long.class);
        assertFalse(demoVenueIds.isEmpty());

        String inClause = demoVenueIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
        Object[] args = demoVenueIds.toArray();
        String faqSql = "SELECT COUNT(*) FROM venue_faqs WHERE venue_id IN (" + inClause + ")";
        String promoSql = "SELECT COUNT(*) FROM promotions WHERE venue_id IN (" + inClause + ")";

        long faqBefore = jdbc.queryForObject(faqSql, Long.class, args);
        long promoBefore = jdbc.queryForObject(promoSql, Long.class, args);
        assertTrue(faqBefore >= 6, "expected at least 3 FAQs per demo venue");

        seeder.run(new DefaultApplicationArguments(new String[0]));

        assertEquals(faqBefore, jdbc.queryForObject(faqSql, Long.class, args));
        assertEquals(promoBefore, jdbc.queryForObject(promoSql, Long.class, args));

        for (long venueId : demoVenueIds) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM venue_faqs
                    WHERE venue_id = ? AND intent = 'RESERVATION' AND is_active = true
                    """, Integer.class, venueId));
            VenueConfig config = jdbc.queryForObject(
                    "SELECT timezone, business_day_cutoff_hour FROM venues WHERE id = ?",
                    (rs, row) -> new VenueConfig(ZoneId.of(rs.getString("timezone")), rs.getInt("business_day_cutoff_hour")),
                    venueId);
            LocalDate current = businessDates.currentBusinessDate(config.zone(), config.cutoff());
            assertTrue(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM promotions
                    WHERE venue_id = ? AND is_active = true AND starts_on <= ? AND ends_on >= ?
                    """, Integer.class, venueId, current, current) >= 1);
        }
    }

    private long insertVenue(String code, boolean active) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, address, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, void_manager_approval_threshold, opening_hours, is_active, created_at, updated_at)
                VALUES (?, ?, 'Addr', 'Asia/Jakarta', 6, 0, 0, 500000, '{}'::jsonb, ?, ?, ?)
                RETURNING id
                """, Long.class, code, "Venue " + code, active, now, now);
    }

    private String trueCode() {
        return FIXTURE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private record VenueConfig(ZoneId zone, int cutoff) {
    }
}
