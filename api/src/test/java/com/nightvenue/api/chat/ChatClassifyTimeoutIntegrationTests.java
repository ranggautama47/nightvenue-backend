package com.nightvenue.api.chat;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nightvenue.seed.enabled=true",
                "spring.data.redis.timeout=PT1S",
                "server.tomcat.remoteip.internal-proxies=127[.]0[.]0[.]1"
        })
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ChatClassifyTimeoutIntegrationTests {

    private static final String CHAT_URL = "/api/v1/chat";
    private static final String FIXTURE_PREFIX = "CHATTMO-";

    private static ChatClassifierStub stub;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ChatProperties chatProperties;

    private int originalRateLimit;
    private long activeVenueId;

    @BeforeAll
    static void startStub() throws Exception {
        stub = new ChatClassifierStub();
    }

    @AfterAll
    static void stopStub() throws Exception {
        if (stub != null) {
            stub.close();
        }
    }

    @DynamicPropertySource
    static void registerMlProperties(DynamicPropertyRegistry registry) {
        registry.add("nightvenue.ml.service-url", () -> stub.url());
        registry.add("nightvenue.ml.internal-token", () -> "timeout-test-token");
    }

    @BeforeEach
    void setUp() {
        originalRateLimit = chatProperties.getRateLimitPerMin();
        chatProperties.setRateLimitPerMin(1000);
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        cleanupFixtures();
        activeVenueId = insertVenue(fixtureCode());
    }

    @AfterEach
    void tearDown() {
        chatProperties.setRateLimitPerMin(originalRateLimit);
        cleanupFixtures();
    }

    @Test
    void slowBodyTimeoutFallsBackWithinBudget() throws Exception {
        stub.delayHeaders(0);
        stub.delayBody(5000);
        stub.respond(200, "{\"intent\":\"OPENING_HOURS\",\"confidence\":0.9,\"modelVersion\":\"k-v1\"}");

        long start = System.nanoTime();
        mvc.perform(post(CHAT_URL)
                        .contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"jam buka?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("FALLBACK"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        Boolean mlAvailable = jdbc.queryForObject(
                "SELECT ml_available FROM chat_route_logs WHERE venue_id = ?", Boolean.class, activeVenueId);
        assertEquals(Boolean.FALSE, mlAvailable);
        Integer classifierMs = jdbc.queryForObject(
                "SELECT classifier_ms FROM chat_route_logs WHERE venue_id = ?", Integer.class, activeVenueId);
        System.out.println("CHAT_TIMEOUT_MEASURED classifier_ms=" + classifierMs + " total_elapsed_ms=" + elapsedMs);
        assertTrue(classifierMs != null && classifierMs >= 1500 && classifierMs <= 2500,
                "classifier_ms out of expected range: " + classifierMs);
        assertTrue(elapsedMs < 3500, "Expected elapsed < 3500 ms but measured " + elapsedMs + " ms");
    }

    private void cleanupFixtures() {
        jdbc.update("DELETE FROM chat_route_logs WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", FIXTURE_PREFIX + "%");
    }

    private long insertVenue(String code) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, address, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, void_manager_approval_threshold, opening_hours, is_active, created_at, updated_at)
                VALUES (?, ?, 'Addr', 'Asia/Jakarta', 6, 0, 0, 500000, '{"mon":["18:00","02:00"]}'::jsonb, true, ?, ?)
                RETURNING id
                """, Long.class, code, "Venue " + code, now, now);
    }

    private String fixtureCode() {
        return FIXTURE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
