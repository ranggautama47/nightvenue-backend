package com.nightvenue.api.forecast;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class MlStub implements AutoCloseable {
    enum Mode {
        SUCCESS,
        SERVER_500,
        UNAUTHORIZED,
        VALIDATION_422_INSUFFICIENT,
        VALIDATION_422_INVALID_SERIES,
        PYDANTIC_422,
        NOT_FOUND_404,
        HTML_502,
        MALFORMED_200,
        MISSING_FIELD_200,
        ECHO_INGREDIENT_ID,
        HANG,
        DELAYED_BODY
    }

    private static final String SUCCESS_BODY = """
            {"ingredientId":7,"unit":"ML","horizonDays":7,"predictedQuantity":123.456,
             "selectedModel":"REGRESSION","modelName":"ols_weekday_trend","modelMae":2.1,
             "baselineMae":3.2,"inputDays":28,"asOfBusinessDate":"2026-01-01",
             "regressionModelName":"ols_weekday_trend","baselineModelName":"baseline_dow_mean"}
            """;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch hangRelease = new CountDownLatch(1);
    private final CountDownLatch bodyStarted = new CountDownLatch(1);
    private final CountDownLatch bodyRelease = new CountDownLatch(1);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Mode mode;
    volatile String method;
    volatile String path;
    volatile String token;
    volatile String contentType;
    volatile String body;

    MlStub(Mode mode) throws IOException {
        this.mode = mode;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            token = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            switch (this.mode) {
                case HANG -> {
                    try {
                        hangRelease.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 200, SUCCESS_BODY, "application/json");
                }
                case DELAYED_BODY -> respondWithHeldBody(exchange);
                case SERVER_500 -> respond(exchange, 500, "{\"detail\":\"internal\"}", "application/json");
                case UNAUTHORIZED -> respond(exchange, 401, "{\"detail\":\"unauthorized\"}", "application/json");
                case VALIDATION_422_INSUFFICIENT -> respond(
                        exchange, 422,
                        "{\"code\":\"INSUFFICIENT_DATA\",\"message\":\"short history\"}",
                        "application/json");
                case VALIDATION_422_INVALID_SERIES -> respond(
                        exchange, 422,
                        "{\"code\":\"INVALID_SERIES\",\"message\":\"invalid dates\"}",
                        "application/json");
                case PYDANTIC_422 -> respond(
                        exchange, 422, "{\"detail\":[{\"msg\":\"invalid\"}]}", "application/json");
                case NOT_FOUND_404 -> respond(exchange, 404, "{}", "application/json");
                case HTML_502 -> respond(exchange, 502, "<html>private upstream marker</html>", "text/html");
                case MALFORMED_200 -> respond(exchange, 200, "{not-json", "application/json");
                case MISSING_FIELD_200 -> respond(
                        exchange, 200, SUCCESS_BODY.replace("\"baselineMae\":3.2,", ""), "application/json");
                case ECHO_INGREDIENT_ID -> respond(
                        exchange, 200, echoIngredientId(body), "application/json");
                case SUCCESS -> respond(exchange, 200, SUCCESS_BODY, "application/json");
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requestCount() {
        return requestCount.get();
    }

    boolean awaitBodyStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return bodyStarted.await(timeout, unit);
    }

    boolean bodyIsHeld() {
        return bodyRelease.getCount() > 0;
    }

    @Override
    public void close() {
        hangRelease.countDown();
        bodyRelease.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private void respondWithHeldBody(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        try {
            OutputStream output = exchange.getResponseBody();
            output.write("{\"ingredientId\":7,".getBytes(StandardCharsets.UTF_8));
            output.flush();
            bodyStarted.countDown();
            bodyRelease.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange,
            int status,
            String body,
            String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String echoIngredientId(String requestBody) {
        String key = "\"ingredientId\":";
        int start = requestBody.indexOf(key) + key.length();
        int end = requestBody.indexOf(',', start);
        String ingredientId = requestBody.substring(start, end);
        return SUCCESS_BODY.replace("\"ingredientId\":7", "\"ingredientId\":" + ingredientId);
    }
}
