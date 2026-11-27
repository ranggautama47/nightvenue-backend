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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class ChatApiIntegrationTests {

    private static final String CHAT_URL = "/api/v1/chat";
    private static final String FIXTURE_PREFIX = "CHATAPI-";
    private static final String INTERNAL_TOKEN = "test-ml-internal-token-xyz";
    private static final String OPENING_HOURS_JSON =
            "{\"mon\":[\"18:00\",\"02:00\"],\"tue\":[\"18:00\",\"02:00\"]}";

    private static ChatClassifierStub stub;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ChatProperties chatProperties;
    @Autowired @Qualifier("redisContainer") GenericContainer<?> redisContainer;

    private int originalRateLimit;
    private long activeVenueId;
    private long inactiveVenueId;

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
        assertEquals(0, chatProperties.getConfidenceThreshold()
                .compareTo(new BigDecimal("0.2663504950761663")),
                "Confidence threshold config drift detected");
        originalRateLimit = chatProperties.getRateLimitPerMin();
        chatProperties.setRateLimitPerMin(1000);

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        cleanupFixtures();

        stub.delayHeaders(0);
        stub.delayBody(0);
        stub.respond(200, "{\"intent\":\"OPENING_HOURS\",\"confidence\":0.9,\"modelVersion\":\"k-v1\"}");

        activeVenueId = insertVenue(fixtureCode(), true, "Moon Harbor", "North District, Jakarta");
        inactiveVenueId = insertVenue(fixtureCode(), false, "Closed Venue", "Nowhere");
    }

    @AfterEach
    void tearDown() {
        chatProperties.setRateLimitPerMin(originalRateLimit);
        unpauseRedisIfPaused();
        cleanupFixtures();
    }

    private void cleanupFixtures() {
        jdbc.update("DELETE FROM chat_route_logs WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venue_faqs WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", FIXTURE_PREFIX + "%");
    }

    @Test
    void openingHoursAtOrAboveThetaReturnsLocalAndWritesRouteLog() throws Exception {
        mvc.perform(post(CHAT_URL)
                        .contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"  jam buka?  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("LOCAL"))
                .andExpect(jsonPath("$.intent").value("OPENING_HOURS"))
                .andExpect(jsonPath("$.modelVersion").value("k-v1"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("Senin 18:00-02:00")));

        var lastReq = stub.lastRequest();
        assertNotNull(lastReq);
        assertEquals(INTERNAL_TOKEN, lastReq.token());
        assertEquals("jam buka?", lastReq.message());

        assertEquals(1, countRouteLogs(activeVenueId));
        var row = jdbc.queryForMap("SELECT * FROM chat_route_logs WHERE venue_id = ?", activeVenueId);
        assertEquals("LOCAL", row.get("route"));
        assertEquals("OPENING_HOURS", row.get("intent"));
        assertEquals(0, new BigDecimal("0.9000").compareTo((BigDecimal) row.get("confidence")));
        assertEquals(Boolean.TRUE, row.get("ml_available"));
        assertNotNull(row.get("classifier_ms"));
        assertNull(row.get("llm_provider"));
        assertNull(row.get("llm_ms"));
        assertNull(row.get("input_tokens"));
        assertNull(row.get("output_tokens"));
        assertNull(row.get("estimated_cost_usd"));
        assertNotNull(row.get("created_at"));
    }

    @Test
    void generalVenueInfoWithoutFaqUsesNameAndAddress() throws Exception {
        stub.respond(200, "{\"intent\":\"GENERAL_VENUE_INFORMATION\",\"confidence\":0.8,\"modelVersion\":\"k-v1\"}");
        mvc.perform(post(CHAT_URL)
                        .contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"info venue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("LOCAL"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("Moon Harbor"),
                        org.hamcrest.Matchers.containsString("North District, Jakarta"))));
    }

    @Test
    void reservationWithFaqReturnsStoredTemplate() throws Exception {
        jdbc.update("INSERT INTO venue_faqs (venue_id, intent, answer_template, is_active) VALUES (?, 'RESERVATION', 'Walk-in only', true)",
                activeVenueId);
        stub.respond(200, "{\"intent\":\"RESERVATION\",\"confidence\":0.85,\"modelVersion\":\"k-v1\"}");
        mvc.perform(post(CHAT_URL)
                        .contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"bisa reservasi?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("LOCAL"))
                .andExpect(jsonPath("$.answer").value("Walk-in only"));
    }

    @Test
    void routingThresholdsAndClassifierDownBehaviors() throws Exception {
        // Just below theta -> ADVANCED_AI_REQUIRED
        stub.respond(200, "{\"intent\":\"OPENING_HOURS\",\"confidence\":0.2663504950761662,\"modelVersion\":\"k-v1\"}");
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"jam buka\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("ADVANCED_AI_REQUIRED"));

        // ADVANCED_CONVERSATION -> ADVANCED_AI_REQUIRED
        stub.respond(200, "{\"intent\":\"ADVANCED_CONVERSATION\",\"confidence\":0.99,\"modelVersion\":\"k-v1\"}");
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"rekomendasi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("ADVANCED_AI_REQUIRED"));

        // OUT_OF_SCOPE >= theta -> REFUSED
        stub.respond(200, "{\"intent\":\"OUT_OF_SCOPE\",\"confidence\":0.9,\"modelVersion\":\"k-v1\"}");
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"siapa presiden\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("REFUSED"));

        // OUT_OF_SCOPE < theta -> ADVANCED_AI_REQUIRED
        stub.respond(200, "{\"intent\":\"OUT_OF_SCOPE\",\"confidence\":0.1,\"modelVersion\":\"k-v1\"}");
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"apa kabar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("ADVANCED_AI_REQUIRED"));

        // Classifier down (503) + matched fallback -> FALLBACK
        stub.respond(503, "down");
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"jam buka?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("FALLBACK"))
                .andExpect(jsonPath("$.intent").value(nullValue()))
                .andExpect(jsonPath("$.confidence").value(nullValue()))
                .andExpect(jsonPath("$.modelVersion").value(nullValue()));

        // Classifier down (503) + unmatched -> ADVANCED_AI_REQUIRED
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"pertanyaan bebas\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("ADVANCED_AI_REQUIRED"))
                .andExpect(jsonPath("$.intent").value(nullValue()))
                .andExpect(jsonPath("$.confidence").value(nullValue()))
                .andExpect(jsonPath("$.modelVersion").value(nullValue()));
    }

    @Test
    void validationAndNotFoundCases() throws Exception {
        // 422 cases
        mvc.perform(post(CHAT_URL).contentType("application/json").content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(CHAT_URL).contentType("application/json").content("{\"venueId\":0,\"message\":\"hi\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(CHAT_URL).contentType("application/json").content("{\"venueId\":-1,\"message\":\"hi\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(CHAT_URL).contentType("application/json").content("{\"venueId\":" + activeVenueId + "}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(CHAT_URL).contentType("application/json").content("{\"venueId\":" + activeVenueId + ",\"message\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(CHAT_URL).contentType("application/json").content("{\"venueId\":" + activeVenueId + ",\"message\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());

        String exactly500 = "a".repeat(500);
        String over500 = "a".repeat(501);
        String emoji500 = "\uD83D\uDE00".repeat(500); // 500 code points, 1000 UTF-16 chars
        String emoji501 = "\uD83D\uDE00".repeat(501);

        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"" + over500 + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"" + emoji501 + "\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 400 cases
        mvc.perform(post(CHAT_URL).contentType("application/json").content("{bad json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":\"abc\",\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest());

        // 404 cases (unknown vs inactive identical shape)
        String unknownBody = mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":999999999,\"message\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String inactiveBody = mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + inactiveVenueId + ",\"message\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertEquals(unknownBody, inactiveBody);

        assertEquals(0, countRouteLogs(activeVenueId));
        assertEquals(0, countRouteLogs(inactiveVenueId));

        // 500 code points & 500 emoji accepted -> 200
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"" + exactly500 + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"" + emoji500 + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rateLimitBurstAndOrdering() throws Exception {
        chatProperties.setRateLimitPerMin(10);
        for (int i = 0; i < 10; i++) {
            mvc.perform(post(CHAT_URL).contentType("application/json")
                            .content("{\"venueId\":" + activeVenueId + ",\"message\":\"jam buka\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"jam buka\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Ordering proof: rate limit runs before body parse
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        chatProperties.setRateLimitPerMin(1);
        mvc.perform(post(CHAT_URL).contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"jam buka\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(CHAT_URL).contentType("application/json").content("{malformed"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void redisOutageReturns503WithChatDetail() throws Exception {
        pauseRedis();
        try {
            mvc.perform(post(CHAT_URL).contentType("application/json")
                            .content("{\"venueId\":" + activeVenueId + ",\"message\":\"jam buka\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value("Chat service temporarily unavailable"));
            assertEquals(0, countRouteLogs(activeVenueId));
        } finally {
            unpauseRedisIfPaused();
        }
    }

    @Test
    void securityRules() throws Exception {
        mvc.perform(get(CHAT_URL)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());

        // Resource-server BearerTokenAuthenticationFilter rejects invalid bearer tokens with 401 before permitAll
        mvc.perform(post(CHAT_URL)
                        .header("Authorization", "Bearer invalid-token")
                        .contentType("application/json")
                        .content("{\"venueId\":" + activeVenueId + ",\"message\":\"jam buka\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logPrivacyNeverLogsMessageSentinelOrInternalToken() throws Exception {
        String sentinel = "NV_CHAT_LOG_SENTINEL_9xK2mP";
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level originalLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            // success
            mvc.perform(post(CHAT_URL).contentType("application/json")
                            .content("{\"venueId\":" + activeVenueId + ",\"message\":\"" + sentinel + "\"}"))
                    .andExpect(status().isOk());
            // classifier down
            stub.respond(503, "down");
            mvc.perform(post(CHAT_URL).contentType("application/json")
                            .content("{\"venueId\":" + activeVenueId + ",\"message\":\"" + sentinel + "\"}"))
                    .andExpect(status().isOk());
            // 404
            mvc.perform(post(CHAT_URL).contentType("application/json")
                            .content("{\"venueId\":999999999,\"message\":\"" + sentinel + "\"}"))
                    .andExpect(status().isNotFound());
            // 422
            mvc.perform(post(CHAT_URL).contentType("application/json")
                            .content("{\"venueId\":0,\"message\":\"" + sentinel + "\"}"))
                    .andExpect(status().isUnprocessableEntity());
            // 429
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
            chatProperties.setRateLimitPerMin(1);
            mvc.perform(post(CHAT_URL).contentType("application/json")
                            .content("{\"venueId\":" + activeVenueId + ",\"message\":\"" + sentinel + "\"}"))
                    .andExpect(status().isOk());
            mvc.perform(post(CHAT_URL).contentType("application/json")
                            .content("{\"venueId\":" + activeVenueId + ",\"message\":\"" + sentinel + "\"}"))
                    .andExpect(status().isTooManyRequests());

            StringBuilder logBuilder = new StringBuilder();
            for (ILoggingEvent event : appender.list) {
                logBuilder.append(event.getFormattedMessage()).append("\n");
                if (event.getThrowableProxy() != null) {
                    var tp = event.getThrowableProxy();
                    logBuilder.append(tp.getClassName()).append(": ").append(tp.getMessage()).append("\n");
                    if (tp.getStackTraceElementProxyArray() != null) {
                        for (var step : tp.getStackTraceElementProxyArray()) {
                            logBuilder.append(step.getSTEAsString()).append("\n");
                        }
                    }
                }
            }
            String logs = logBuilder.toString();
            assertFalse(logs.contains(sentinel), "Message sentinel must never appear in logs");
            assertFalse(logs.contains(INTERNAL_TOKEN), "ML internal token must never appear in logs");

            var rows = jdbc.queryForList("SELECT route, intent, llm_provider FROM chat_route_logs WHERE venue_id = ?", activeVenueId);
            for (var r : rows) {
                assertFalse(r.toString().contains(sentinel), "Message sentinel must never appear in chat_route_logs");
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
            root.setLevel(originalLevel);
        }
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

    private int countRouteLogs(long venueId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_route_logs WHERE venue_id = ?", Integer.class, venueId);
        return count == null ? 0 : count;
    }

    private void pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(redisContainer.getContainerId()).exec();
    }

    private void unpauseRedisIfPaused() {
        try {
            var state = DockerClientFactory.instance().client()
                    .inspectContainerCmd(redisContainer.getContainerId()).exec().getState();
            if (Boolean.TRUE.equals(state.getPaused())) {
                DockerClientFactory.instance().client().unpauseContainerCmd(redisContainer.getContainerId()).exec();
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (System.nanoTime() < deadline) {
                    try {
                        redis.getConnectionFactory().getConnection().ping();
                        return;
                    } catch (Exception ignored) {
                        Thread.sleep(100);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
