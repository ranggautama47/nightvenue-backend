package com.nightvenue.api.catalog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nightvenue.seed.enabled=true")
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CatalogDatabaseIntegrityIntegrationTests {
    private static final String FIXTURE_PREFIX = "N4A-FK-";
    private static final long MISSING_INGREDIENT_ID = Long.MAX_VALUE;

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @LocalServerPort int port;

    private long recipeId;
    private long productId;

    @BeforeEach
    void createRecipeFixture() {
        long venueId = jdbc.queryForObject("SELECT id FROM venues ORDER BY id LIMIT 1", Long.class);
        productId = jdbc.queryForObject("""
                INSERT INTO products
                    (venue_id, sku, name, price, is_bottle_keepable, is_active, version, created_at, updated_at)
                VALUES (?, ?, 'FK error test product', 100, false, true, 0, now(), now())
                RETURNING id
                """, Long.class, venueId, FIXTURE_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        recipeId = jdbc.queryForObject("""
                INSERT INTO recipes (product_id, version, updated_at)
                VALUES (?, 0, now())
                RETURNING id
                """, Long.class, productId);
    }

    @AfterEach
    void removeRecipeFixture() {
        jdbc.update("DELETE FROM recipe_items WHERE recipe_id = ?", recipeId);
        jdbc.update("DELETE FROM recipes WHERE id = ?", recipeId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void unexpectedForeignKeyViolationReturnsGenericProblemAndSanitizedLog() throws Exception {
        Logger apiLogger = (Logger) LoggerFactory.getLogger("com.nightvenue.api");
        ch.qos.logback.classic.Level previousLevel = apiLogger.getLevel();
        apiLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        apiLogger.addAppender(appender);
        String traceId = "n4a-" + UUID.randomUUID();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/test/db-integrity/fk/" + recipeId + "/" + MISSING_INGREDIENT_ID))
                    .header("Authorization", bearer("ADMIN"))
                    .header("X-Request-Id", traceId)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(500, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/problem+json"));
            assertTrue(response.body().contains("\"detail\":\"Unexpected error\""));
            assertTrue(response.body().contains(traceId));
            assertFalse(response.body().contains("SQL"));
            assertFalse(response.body().contains("recipe_items"));
            assertFalse(response.body().contains(Long.toString(MISSING_INGREDIENT_ID)));
            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getFormattedMessage().contains("exceptionClass=")
                            && event.getFormattedMessage().contains("sqlState=23503")
                            && event.getFormattedMessage().contains(traceId)));

            StringBuilder captured = new StringBuilder();
            appender.list.stream()
                    .filter(event -> event.getLoggerName().startsWith("com.nightvenue"))
                    .forEach(event -> {
                        captured.append(event.getFormattedMessage()).append('\n');
                        if (event.getThrowableProxy() != null) {
                            captured.append(event.getThrowableProxy().getClassName()).append(':')
                                    .append(event.getThrowableProxy().getMessage()).append('\n');
                            for (var frame : event.getThrowableProxy().getStackTraceElementProxyArray()) {
                                captured.append(frame.getSTEAsString()).append('\n');
                            }
                        }
                    });
            String logs = captured.toString();
            assertFalse(appender.list.isEmpty(), "Expected an application log event for the database failure");
            assertFalse(logs.contains("SQL"));
            assertFalse(logs.contains("recipe_items"));
            assertFalse(logs.contains(Long.toString(MISSING_INGREDIENT_ID)));
            assertFalse(logs.contains("recipe_items_ingredient_id_fkey"));
        } finally {
            apiLogger.detachAppender(appender);
            appender.stop();
            apiLogger.setLevel(previousLevel);
        }
    }

    private String bearer(String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("nightvenue")
                .subject("1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("role", role)
                .build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
