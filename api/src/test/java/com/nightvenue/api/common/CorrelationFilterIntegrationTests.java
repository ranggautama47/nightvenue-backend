package com.nightvenue.api.common;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nightvenue.seed.enabled=true")
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CorrelationFilterIntegrationTests {

    @LocalServerPort
    int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void correlationIdPropagatedToResponseHeaderAndBodyOn401() throws Exception {
        String requestId = "abc-123";
        String url = "http://127.0.0.1:" + port + "/api/v1/categories";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-Request-Id", requestId)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), "Expected 401 Unauthorized for missing auth");
        assertEquals(requestId, response.headers().firstValue("X-Request-Id").orElseThrow(),
                "X-Request-Id response header must echo the request value");

        String body = response.body();
        assertTrue(body.contains("traceId"), "Response body must contain traceId property");
        assertTrue(body.contains(requestId), "Response body traceId must equal the request X-Request-Id");
    }
}