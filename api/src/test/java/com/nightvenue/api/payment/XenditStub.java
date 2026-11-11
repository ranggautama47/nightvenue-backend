package com.nightvenue.api.payment;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDK HttpServer stub for the Xendit Payment Requests v3 API. No WireMock in
 * the build (Phase 0b). Modes are switched per test; the handler never touches
 * anything secret. Uses the application JdbcTemplate for the FENCE/FAIL_TX2
 * sabotage modes so it can act on the same Testcontainers database.
 */
public final class XenditStub {
    public static final String QR = "00020101021228-TEST-QR-PAYLOAD-NOT-REAL";
    public static final String REQUEST_ID = "pr-test-0001";
    public static final String LONG_REQUEST_ID = "x".repeat(40);
    public static final String SECRET = "test-secret-not-real";
    public static final int READ_TIMEOUT_MS = 2000;

    public enum Mode { SUCCESS, NO_QR, NO_ID, LONG_ID, VALIDATION, SERVER_500, TIMEOUT, FENCE, FAIL_TX2, BLOCKED, STATUS_SUCCESS, STATUS_404, STATUS_500, STATUS_401, STATUS_403, STATUS_TIMEOUT, STATUS_TIMEOUT_LONG, STATUS_MALFORMED, STATUS_UNMATCHED, STATUS_FAILED, STATUS_EXPIRED, STATUS_CANCELED, STATUS_BLOCKED }

    private static final HttpServer SERVER;
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static volatile Mode mode = Mode.SUCCESS;
    private static volatile long currentOrderId;
    private static volatile String currentKey;
    private static volatile JdbcTemplate jdbc;
    private static volatile CountDownLatch blockLatch = new CountDownLatch(0);
    private static volatile CountDownLatch statusSeen = new CountDownLatch(0);
    private static volatile String statusReference = "nv_pay_1";
    private static final java.util.concurrent.ConcurrentHashMap<String, String> REQUEST_REFERENCES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> REQUEST_STATUSES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> REQUEST_HTTP_ERRORS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static volatile String lastAuth;
    public static volatile String lastApiVersion;
    public static volatile String lastBody;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/v3/payment_requests", exchange -> {
            COUNT.incrementAndGet();
            if ("GET".equals(exchange.getRequestMethod())) {
                String[] pathParts = exchange.getRequestURI().getPath().split("/");
                String requestedId = pathParts[pathParts.length - 1];
                Integer perRequestError = REQUEST_HTTP_ERRORS.get(requestedId);
                if (perRequestError != null) {
                    respond(exchange, perRequestError, "{}");
                    return;
                }
                switch (mode) {
                    case STATUS_BLOCKED -> {
                        statusSeen.countDown();
                        try { blockLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        respond(exchange, 200, statusBody(requestedId, statusFor(requestedId, "SUCCEEDED")));
                    }
                    case STATUS_404 -> respond(exchange, 404, "{}");
                    case STATUS_500 -> respond(exchange, 500, "{}");
                    case STATUS_401 -> respond(exchange, 401, "{}");
                    case STATUS_403 -> respond(exchange, 403, "{}");
                    case STATUS_MALFORMED -> respond(exchange, 200, "not-json");
                    case STATUS_TIMEOUT -> {
                        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        respond(exchange, 200, statusBody(requestedId, statusFor(requestedId, "SUCCEEDED")));
                    }
                    case STATUS_TIMEOUT_LONG -> {
                        try { Thread.sleep(6000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        respond(exchange, 200, statusBody(requestedId, statusFor(requestedId, "SUCCEEDED")));
                    }
                    case STATUS_UNMATCHED -> { statusReference = "unknown-ref"; respond(exchange, 200, statusBody(requestedId, "SUCCEEDED")); }
                    case STATUS_FAILED -> respond(exchange, 200, statusBody(requestedId, statusFor(requestedId, "FAILED")));
                    case STATUS_EXPIRED -> respond(exchange, 200, statusBody(requestedId, statusFor(requestedId, "EXPIRED")));
                    case STATUS_CANCELED -> respond(exchange, 200, statusBody(requestedId, statusFor(requestedId, "CANCELED")));
                    default -> respond(exchange, 200, statusBody(requestedId, statusFor(requestedId, "SUCCEEDED")));
                }
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            lastApiVersion = exchange.getRequestHeaders().getFirst("api-version");
            lastBody = new String(body, StandardCharsets.UTF_8);
            Mode m = mode;
            switch (m) {
                case BLOCKED -> {
                    try {
                        blockLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 201, successBody(REQUEST_ID, QR));
                }
                case TIMEOUT -> {
                    try {
                        Thread.sleep(READ_TIMEOUT_MS * 5L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 201, successBody(REQUEST_ID, QR));
                }
                case NO_QR -> respond(exchange, 201, "{\"id\":\"" + REQUEST_ID + "\",\"status\":\"PENDING\"}");
                case NO_ID -> respond(exchange, 201, "{\"status\":\"PENDING\"}");
                case LONG_ID -> respond(exchange, 201, "{\"id\":\"" + LONG_REQUEST_ID + "\",\"status\":\"PENDING\"}");
                case VALIDATION -> respond(exchange, 400, "{\"errorCode\":\"API_VALIDATION_ERROR\",\"message\":\"bad\"}");
                case SERVER_500 -> respond(exchange, 500, "{\"errorCode\":\"SERVER_ERROR\"}");
                case FENCE -> {
                    // move the payment off SENDING between TX1 and TX2 (fencing probe)
                    jdbc.update("UPDATE payments SET charge_state = 'ACCEPTED', version = version + 1 " +
                            "WHERE order_id = ? AND charge_state = 'SENDING'", currentOrderId);
                    respond(exchange, 201, successBody(REQUEST_ID, QR));
                }
                case FAIL_TX2 -> {
                    // remove the idempotency row so complete() finds nothing -> TX2 must roll back
                    jdbc.update("DELETE FROM idempotency_keys WHERE key::text = ?", currentKey);
                    respond(exchange, 201, successBody(REQUEST_ID, QR));
                }
                default -> respond(exchange, 201, successBody(REQUEST_ID, QR));
            }
        });
        SERVER.setExecutor(Executors.newCachedThreadPool());
        SERVER.start();
    }

    private static String statusBody() {
        return statusBody(REQUEST_ID, "SUCCEEDED");
    }

    private static String statusFor(String requestId, String fallback) {
        return REQUEST_STATUSES.getOrDefault(requestId, fallback);
    }

    private static String referenceFor(String requestId) {
        return REQUEST_REFERENCES.getOrDefault(requestId, statusReference);
    }

    private static String statusBody(String requestId, String status) {
        return "{\"id\":\"" + requestId + "\",\"reference_id\":\"" + referenceFor(requestId) + "\",\"status\":\"" + status + "\",\"request_amount\":10000,\"currency\":\"IDR\",\"country\":\"ID\",\"channel_code\":\"QRIS\",\"type\":\"PAY\",\"payment_id\":\"pay-test\"}";
    }

    private static String successBody(String id, String qr) {
        return "{\"id\":\"" + id + "\",\"status\":\"PENDING\",\"actions\":[{\"action\":\"QR_STRING\",\"value\":\"" + qr + "\"}]}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // client already gone (e.g. read-timeout path)
        }
    }

    public static int port() {
        return SERVER.getAddress().getPort();
    }

    public static void reset(Mode newMode, long orderId, String idempotencyKey, JdbcTemplate template) {
        mode = newMode;
        statusReference = "nv_pay_1";
        currentOrderId = orderId;
        currentKey = idempotencyKey;
        jdbc = template;
        COUNT.set(0);
        blockLatch = new CountDownLatch(1);
        statusSeen = new CountDownLatch(1);
        REQUEST_REFERENCES.clear();
        REQUEST_STATUSES.clear();
        REQUEST_HTTP_ERRORS.clear();
        lastAuth = null;
        lastApiVersion = null;
        lastBody = null;
    }

    /** Per-request Status API response, so a batch can mix outcomes. */
    public static void mapRequest(String requestId, String reference, String status) {
        REQUEST_REFERENCES.put(requestId, reference);
        REQUEST_STATUSES.put(requestId, status);
    }

    /** Per-request HTTP failure for the Status API (e.g. 500 for one row in a batch). */
    public static void mapHttpError(String requestId, int httpStatus) {
        REQUEST_HTTP_ERRORS.put(requestId, httpStatus);
    }

    /** Removes a per-request HTTP failure so the next cycle can succeed. */
    public static void clearHttpError(String requestId) {
        REQUEST_HTTP_ERRORS.remove(requestId);
    }

    /** Switches the handler mode without clearing per-request mappings. */
    public static void setMode(Mode newMode) {
        mode = newMode;
    }

    /** Blocks until the stub has received a Status API GET (STATUS_BLOCKED mode). */
    public static boolean awaitStatusRequest(int seconds) throws InterruptedException {
        return statusSeen.await(seconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static int count() {
        return COUNT.get();
    }

    public static void releaseBlock() {
        blockLatch.countDown();
    }

    private XenditStub() {
    }
}
