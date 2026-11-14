package com.nightvenue.api.payment.xendit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step-2 unit tests: payload builder, response extraction, error classification, applied timeouts. */
class XenditClientTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer server;
    private static int port;
    private static volatile String lastAuthHeader;
    private static volatile String lastApiVersion;
    private static volatile String lastBody;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v3/payment_requests", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastApiVersion = exchange.getRequestHeaders().getFirst("api-version");
            lastBody = new String(body, StandardCharsets.UTF_8);
            byte[] response = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    static final String RESPONSE_BODY = """
            {"id":"pr-abc-123","status":"PENDING","actions":[{"action":"QR_STRING","value":"000201-QR-DATA"}]}
            """;

    private XenditClient client(String baseUrl, int readTimeoutMs) {
        XenditProperties props = new XenditProperties();
        props.setSecretKey("test-secret-not-real");
        props.setBaseUrl(baseUrl);
        props.setReadTimeoutMs(readTimeoutMs);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient restClient = RestClient.builder().baseUrl(props.getBaseUrl()).requestFactory(factory).build();
        return new XenditClient(restClient, props, MAPPER);
    }

    @Test
    void payload_isFlatExactlySixFields_noPaymentMethodKey() {
        String payload = client("http://localhost:" + port, 2000).buildCreatePayload("nv_pay_1", 15000);
        Map<String, Object> fields = MAPPER.readValue(payload, Map.class);
        assertEquals(Map.of("reference_id", "nv_pay_1", "type", "PAY", "country", "ID",
                "currency", "IDR", "request_amount", 15000, "channel_code", "QRIS"), fields);
    }

    @Test
    void happyPath_2xx_confirmedWithIdAndQr_andHeadersApplied() {
        XenditClient.CreateResult result = client("http://localhost:" + port, 2000).createQris("nv_pay_9", 10000);
        assertEquals(XenditClient.Outcome.CONFIRMED, result.outcome());
        assertEquals("pr-abc-123", result.paymentRequestId());
        assertEquals("000201-QR-DATA", result.qrString());
        assertEquals(200, result.httpStatus());
        // contract: Basic base64(secret + ":"), api-version header, flat body, no idempotency header
        assertEquals("Basic " + Base64.getEncoder().encodeToString("test-secret-not-real:".getBytes(StandardCharsets.UTF_8)),
                lastAuthHeader);
        assertEquals("2024-11-11", lastApiVersion);
        assertTrue(lastBody.contains("\"reference_id\""));
        assertFalse(lastBody.contains("payment_method"));
        assertFalse(lastBody.toUpperCase().contains("IDEMPOTENCY"));
    }

    @Test
    void requestId_over39Chars_orBlank_treatedAsAbsent() {
        assertNull(XenditClient.normalizedRequestId(null));
        assertNull(XenditClient.normalizedRequestId("  "));
        assertNull(XenditClient.normalizedRequestId("x".repeat(40)));
        assertEquals("x".repeat(39), XenditClient.normalizedRequestId("x".repeat(39)));
    }

    @Test
    void qrExtractor_requiresQrStringDescriptor_readsValueUrlOrQrString() {
        assertNull(XenditClient.extractQrString(Map.of()));
        assertNull(XenditClient.extractQrString(Map.of("actions", List.of(
                Map.of("action", "QR_IMAGE", "value", "nope")))));
        assertEquals("qr", XenditClient.extractQrString(Map.of("actions", List.of(
                Map.of("action", "QR_STRING", "value", "qr")))));
        assertEquals("qr2", XenditClient.extractQrString(Map.of("actions", List.of(
                Map.of("descriptor", "QR_STRING", "url", "qr2")))));
    }

    @Test
    void statusApi_responseIdDifferentFromRequestedId_isMalformed() {
        // the stub always answers with id "pr-abc-123"; asking for another id must be MALFORMED,
        // never VERIFIED, so a mismatched response can never settle a payment
        XenditClient.VerificationResult result =
                client("http://localhost:" + port, 2000).getPaymentRequest("pr-requested-1");
        assertEquals(XenditClient.VerificationKind.MALFORMED, result.kind());
        assertNull(result.requestId());
        assertNull(result.referenceId());
        assertNull(result.status());
        assertNull(result.amount());
    }

    @Test
    void readTimeout_actuallyApplies_transportFailureIsAmbiguous() throws IOException {        HttpServer slowServer = HttpServer.create(new InetSocketAddress(0), 0);
        slowServer.createContext("/v3/payment_requests", exchange -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        slowServer.setExecutor(Executors.newSingleThreadExecutor());
        slowServer.start();
        try {
            XenditClient.CreateResult result = client(
                    "http://localhost:" + slowServer.getAddress().getPort(), 200).createQris("nv_pay_t", 1000);
            assertEquals(XenditClient.Outcome.AMBIGUOUS, result.outcome());
            assertEquals(0, result.httpStatus());
            assertNull(result.paymentRequestId());
        } finally {
            slowServer.stop(0);
        }
    }
}
