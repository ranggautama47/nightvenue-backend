package com.nightvenue.api.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ChatClassifierStub implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayHeadersMs;
    private volatile long delayBodyMs;
    private volatile boolean redirect;

    ChatClassifierStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/ml/chat/classify", this::handle);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    int requestCount() { return requests.size(); }
    CapturedRequest lastRequest() { return requests.get(requests.size() - 1); }
    void respond(int status, String body) { this.status = status; this.body = body; redirect = false; }
    void delayHeaders(long millis) { delayHeadersMs = millis; }
    void delayBody(long millis) { delayBodyMs = millis; }
    void redirect() { redirect = true; }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(requestBody);
        String message = root != null && root.get("message") != null ? root.get("message").stringValue() : null;
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                exchange.getRequestHeaders().getFirst("Content-Type"), requestBody, message));
        try {
            if (delayHeadersMs > 0) Thread.sleep(delayHeadersMs);
            if (redirect) {
                exchange.getResponseHeaders().set("Location", "/elsewhere");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, delayBodyMs > 0 ? 0 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                if (delayBodyMs > 0) Thread.sleep(delayBodyMs);
                output.write(bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exchange.close();
        } catch (IOException exception) {
            exchange.close();
        }
    }

    record CapturedRequest(String method, String path, String token, String contentType,
                           String body, String message) {
    }
}
