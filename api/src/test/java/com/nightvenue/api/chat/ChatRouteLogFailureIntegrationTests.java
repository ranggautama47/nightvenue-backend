package com.nightvenue.api.chat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
class ChatRouteLogFailureIntegrationTests {

    private static final String CHAT_URL = "/api/v1/chat";
    private static final String FIXTURE_PREFIX = "CHATLOGFAIL-";
    private static final String INTERNAL_TOKEN = "chatlogfail-internal-token-abc";
    private static final String SENTINEL = "NV_LOGFAIL_SENTINEL_7qT4zR";
    private static final String OPENING_HOURS_JSON =
            "{\"mon\":[\"18:00\",\"02:00\"],\"tue\":[\"18:00\",\"02:00\"]}";

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
        registry.add("nightvenue.ml.internal-token", () -> INTERNAL_TOKEN);
    }

    @BeforeEach
    void setUp() {
        dropConstraintIfExists();
        originalRateLimit = chatProperties.getRateLimitPerMin();
        chatProperties.setRateLimitPerMin(1000);
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        cleanupFixtures();
        stub.delayHeaders(0);
        stub.delayBody(0);
        stub.respond(200, "{\"intent\":\"OPENING_HOURS\",\"confidence\":0.9,\"modelVersion\":\"k-v1\"}");
        activeVenueId = insertVenue(fixtureCode(), true, "LogFail Venue", "Test District, Jakarta");
    }

    @AfterEach
    void tearDown() {
        dropConstraintIfExists();
        chatProperties.setRateLimitPerMin(originalRateLimit);
        cleanupFixtures();
    }

    @Test
    void routeLogInsertFailureIsFailClosedSanitizedAndRecoverable() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level savedLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            // Force a real DB failure on the route-log insert
            jdbc.execute("ALTER TABLE chat_route_logs ADD CONSTRAINT tmp_chat_log_fail" +
                    " CHECK (venue_id <> " + activeVenueId + ") NOT VALID");

            String requestBody = "{\"venueId\":" + activeVenueId + ",\"message\":\"" + SENTINEL + " jam buka\"}";

            // 503 path
            String responseBody = mvc.perform(post(CHAT_URL)
                            .contentType("application/json")
                            .content(requestBody))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value("Chat service temporarily unavailable"))
                    .andExpect(jsonPath("$.answer").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            // Response body must not leak internals (case-insensitive check on lowercased body)
            String lowerBody = responseBody.toLowerCase();
            for (String forbidden : new String[]{
                    "tmp_chat_log_fail", "chat_route_logs", "violates", "constraint",
                    "org.postgresql", "org.springframework", "exception", "token",
                    "nv_logfail_sentinel", "23514", "sql"}) {
                if (lowerBody.contains(forbidden)) {
                    throw new AssertionError("Response body contains forbidden string: " + forbidden);
                }
            }

            // No route-log row was written
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chat_route_logs WHERE venue_id = ?", Integer.class, activeVenueId);
            assertEquals(0, count == null ? 0 : count,
                    "Expected 0 route-log rows on failure path");

            // Captured logs must not contain the sentinel or the internal token
            StringBuilder logText = new StringBuilder();
            for (ILoggingEvent event : appender.list) {
                logText.append(event.getFormattedMessage()).append("\n");
                if (event.getThrowableProxy() != null) {
                    var tp = event.getThrowableProxy();
                    logText.append(tp.getClassName()).append(": ").append(tp.getMessage()).append("\n");
                    if (tp.getStackTraceElementProxyArray() != null) {
                        for (var step : tp.getStackTraceElementProxyArray()) {
                            logText.append(step.getSTEAsString()).append("\n");
                        }
                    }
                }
            }
            String logs = logText.toString();
            if (logs.contains(SENTINEL)) {
                throw new AssertionError("Captured logs contain the sentinel string");
            }
            if (logs.contains(INTERNAL_TOKEN)) {
                throw new AssertionError("Captured logs contain the internal token");
            }

            // Recovery: drop constraint and re-send — must succeed
            dropConstraintIfExists();

            mvc.perform(post(CHAT_URL)
                            .contentType("application/json")
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.route").value("LOCAL"));

            Integer afterCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chat_route_logs WHERE venue_id = ?", Integer.class, activeVenueId);
            assertEquals(1, afterCount == null ? 0 : afterCount,
                    "Expected exactly 1 route-log row after recovery");

        } finally {
            dropConstraintIfExists();
            root.detachAppender(appender);
            appender.stop();
            root.setLevel(savedLevel);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void dropConstraintIfExists() {
        jdbc.execute("ALTER TABLE chat_route_logs DROP CONSTRAINT IF EXISTS tmp_chat_log_fail");
    }

    private void cleanupFixtures() {
        jdbc.update("DELETE FROM chat_route_logs WHERE venue_id IN" +
                " (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", FIXTURE_PREFIX + "%");
    }

    private long insertVenue(String code, boolean active, String name, String address) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, address, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, void_manager_approval_threshold, opening_hours, is_active, created_at, updated_at)
                VALUES (?, ?, ?, 'Asia/Jakarta', 6, 0, 0, 500000, ?::jsonb, ?, ?, ?)
                RETURNING id
                """, Long.class, code, name, address, OPENING_HOURS_JSON, active, now, now);
    }

    private String fixtureCode() {
        return FIXTURE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
