package com.nightvenue.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the production visibility default for Actuator health.
 *
 * <p>{@code application.yml} sets {@code management.endpoint.health.show-details: never}.
 * The {@code test} profile overrides it to {@code always} (see application-test.yml) so other
 * tests can assert component details; this test re-applies the production value explicitly,
 * proving an anonymous caller sees only the aggregate status without a {@code components} block.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoint.health.show-details=never"
        })
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class HealthDetailsVisibilityTests {

    @LocalServerPort
    int port;

    @Test
    void anonymousHealthHidesComponentsWhenShowDetailsIsNever() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""), response.body());
        assertFalse(response.body().contains("\"components\""), response.body());
    }
}
