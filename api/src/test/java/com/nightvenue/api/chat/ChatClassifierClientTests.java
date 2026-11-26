package com.nightvenue.api.chat;

import com.nightvenue.api.forecast.MlProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatClassifierClientTests {
    private static final String TOKEN = "test-chat-internal-token";
    private ChatClassifierStub stub;

    @AfterEach
    void stopStub() {
        if (stub != null) stub.close();
    }

    @Test
    void parsesExactConfidenceAndIgnoresRequiresLlm() throws Exception {
        stub = new ChatClassifierStub();
        stub.respond(200, valid("0.2663504950761663", "false"));
        var outcome = client(stub.url(), TOKEN, 1000, 2, 300).classify("hello");
        assertTrue(outcome.available());
        assertEquals(ChatIntent.OPENING_HOURS, outcome.classification().intent());
        assertEquals(0, outcome.classification().confidence().compareTo(new BigDecimal("0.2663504950761663")));
        assertEquals("chat-modelk-lr-d98a37b805f0f770", outcome.classification().modelVersion());

        for (String extra : List.of("", ",\"requiresLlm\":true", ",\"requiresLlm\":false", ",\"requiresLlm\":\"x\"")) {
            stub.respond(200, "{\"intent\":\"OPENING_HOURS\",\"confidence\":1,\"modelVersion\":\"v\"" + extra + "}");
            assertTrue(client(stub.url(), TOKEN, 1000, 2, 300).classify("x").available());
        }
        stub.respond(200, valid("0.26635049507616630001", null));
        assertEquals(0, client(stub.url(), TOKEN, 1000, 2, 300).classify("x")
                .classification().confidence().compareTo(new BigDecimal("0.26635049507616630001")));
        for (String zeroOne : List.of("0", "1")) {
            stub.respond(200, valid(zeroOne, null));
            assertTrue(client(stub.url(), TOKEN, 1000, 2, 300).classify("x").available());
        }
    }

    @Test
    void postsSingleUtf8MessageField() throws Exception {
        stub = new ChatClassifierStub();
        stub.respond(200, valid("1", null));
        String message = "quote \" newline\n emoji \uD83D\uDE03 café";
        client(stub.url(), TOKEN, 1000, 2, 300).classify(message);
        var request = stub.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("/ml/chat/classify", request.path());
        assertEquals(TOKEN, request.token());
        assertTrue(request.contentType().startsWith("application/json"));
        JsonNode json = new JsonMapper().readTree(request.body());
        assertEquals(1, json.size());
        assertEquals(message, json.get("message").stringValue());
        assertEquals(message, request.message());
    }

    @Test
    void invalidStatusesAndBodiesFailClosed() throws Exception {
        stub = new ChatClassifierStub();
        for (int status : List.of(401, 422, 503, 500)) {
            stub.respond(status, "{}");
            unavailable(client(stub.url(), TOKEN, 1000, 2, 300).classify("private message"));
        }
        stub.redirect();
        unavailable(client(stub.url(), TOKEN, 1000, 2, 300).classify("private message"));
        assertEquals(5, stub.requestCount());
        for (String body : List.of("", "{", "[]", "{\"confidence\":1,\"modelVersion\":\"v\"}",
                "{\"intent\":\"OPENING_HOURS\",\"modelVersion\":\"v\"}",
                "{\"intent\":\"OPENING_HOURS\",\"confidence\":1}",
                "{\"intent\":null,\"confidence\":1,\"modelVersion\":\"v\"}",
                "{\"intent\":\"opening_hours\",\"confidence\":1,\"modelVersion\":\"v\"}",
                "{\"intent\":\"FOO\",\"confidence\":1,\"modelVersion\":\"v\"}",
                "{\"intent\":1,\"confidence\":1,\"modelVersion\":\"v\"}",
                "{\"intent\":\"OPENING_HOURS\",\"confidence\":\"0.9\",\"modelVersion\":\"v\"}",
                "{\"intent\":\"OPENING_HOURS\",\"confidence\":-0.1,\"modelVersion\":\"v\"}",
                "{\"intent\":\"OPENING_HOURS\",\"confidence\":1.0000001,\"modelVersion\":\"v\"}",
                "{\"intent\":\"OPENING_HOURS\",\"confidence\":2,\"modelVersion\":\"v\"}",
                "{\"intent\":\"OPENING_HOURS\",\"confidence\":1,\"modelVersion\":\"\"}",
                "{\"intent\":\"OPENING_HOURS\",\"confidence\":1,\"modelVersion\":\"   \"}",
                "{\"intent\":\"OPENING_HOURS\",\"confidence\":1,\"modelVersion\":1}")) {
            stub.respond(200, body);
            unavailable(client(stub.url(), TOKEN, 1000, 2, 300).classify("x"));
        }
    }

    @Test
    void missingConfigurationAndRefusedConnectionFail() throws Exception {
        stub = new ChatClassifierStub();
        unavailable(client(" ", TOKEN, 1000, 2, 300).classify("x"));
        unavailable(client(stub.url(), " ", 1000, 2, 300).classify("x"));
        assertEquals(0, stub.requestCount());
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        unavailable(client("http://127.0.0.1:" + port, TOKEN, 300, 2, 300).classify("x"));
    }

    @Test
    void totalDeadlineCoversHeadersAndBody() throws Exception {
        stub = new ChatClassifierStub();
        stub.respond(200, valid("1", null));
        stub.delayHeaders(700);
        long start = System.nanoTime();
        unavailable(client(stub.url(), TOKEN, 300, 2, 300).classify("x"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500);
        stub.delayHeaders(0);
        stub.delayBody(700);
        start = System.nanoTime();
        unavailable(client(stub.url(), TOKEN, 300, 2, 300).classify("x"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500);
    }

    @Test
    void bulkheadRejectsConcurrentRequestAndTimeoutDefaultIsUsable() throws Exception {
        stub = new ChatClassifierStub();
        stub.respond(200, valid("1", null));
        stub.delayHeaders(800);
        ChatClassifierClient client = client(stub.url(), TOKEN, 2000, 1, 300);
        FutureTask<ChatClassifierOutcome> first = new FutureTask<>(() -> client.classify("one"));
        Thread thread = new Thread(first);
        thread.start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (stub.requestCount() == 0 && System.nanoTime() < until) Thread.sleep(5);
        unavailable(client.classify("two"));
        assertEquals(1, stub.requestCount());
        assertTrue(first.get(5, TimeUnit.SECONDS).available());
        stub.delayHeaders(0);
        assertTrue(client(stub.url(), TOKEN, 0, 1, 300).classify("fast").available());
    }

    @Test
    void validatesBulkheadSize() {
        assertThrows(IllegalStateException.class, () -> client("", TOKEN, 1000, 0, 300));
        assertThrows(IllegalStateException.class, () -> client("", TOKEN, 1000, -1, 300));
        assertTrue(client("", TOKEN, 1000, 1, 300) != null);
    }

    private static ChatClassifierClient client(String url, String token, int timeout, int concurrency, int rate) {
        return new ChatClassifierClient(new MlProperties(url, token, 1000),
                new ChatProperties(timeout, new BigDecimal("0.5"), rate, concurrency));
    }

    private static String valid(String confidence, String extra) {
        return "{\"intent\":\"OPENING_HOURS\",\"confidence\":" + confidence
                + (extra == null ? "" : ",\"requiresLlm\":" + extra)
                + ",\"modelVersion\":\"chat-modelk-lr-d98a37b805f0f770\"}";
    }

    private static void unavailable(ChatClassifierOutcome outcome) {
        assertFalse(outcome.available());
        assertTrue(outcome.elapsedMs() >= 0);
    }
}
