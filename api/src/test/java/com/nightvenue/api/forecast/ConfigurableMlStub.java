package com.nightvenue.api.forecast;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class ConfigurableMlStub implements AutoCloseable {
    private static final String FORECAST_PATH = "/ml/forecast/inventory";
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedRequest> captured = new CopyOnWriteArrayList<>();
    private volatile Long ingredientIdOverride;

    ConfigurableMlStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(FORECAST_PATH, this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<CapturedRequest> requests() {
        return List.copyOf(captured);
    }

    int requestCount() {
        return captured.size();
    }

    void reset() {
        captured.clear();
        ingredientIdOverride = null;
    }

    void overrideIngredientId(Long ingredientId) {
        ingredientIdOverride = ingredientId;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())
                || !FORECAST_PATH.equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "{}");
            return;
        }
        JsonNode root = mapper.readTree(exchange.getRequestBody().readAllBytes());
        long ingredientId = root.get("ingredientId").longValue();
        String unit = root.get("unit").stringValue();
        int horizonDays = root.get("horizonDays").intValue();
        JsonNode observations = root.get("series");
        List<LocalDate> dates = new ArrayList<>();
        List<BigDecimal> quantities = new ArrayList<>();
        for (JsonNode observation : observations) {
            dates.add(LocalDate.parse(observation.get("businessDate").stringValue()));
            quantities.add(observation.get("quantity").decimalValue());
        }
        captured.add(new CapturedRequest(ingredientId, unit, horizonDays,
                List.copyOf(dates), List.copyOf(quantities),
                exchange.getRequestHeaders().getFirst("X-Internal-Token")));
        long responseIngredientId = ingredientIdOverride == null ? ingredientId : ingredientIdOverride;
        String asOf = dates.get(dates.size() - 1).toString();
        String body = """
                {"ingredientId":%d,"unit":"%s","horizonDays":%d,"predictedQuantity":12.500,
                 "selectedModel":"REGRESSION","modelName":"ols_weekday_trend","modelMae":1.250,
                 "baselineMae":2.500,"inputDays":%d,"asOfBusinessDate":"%s",
                 "regressionModelName":"ols_weekday_trend","baselineModelName":"baseline_dow_mean"}
                """.formatted(responseIngredientId, unit, horizonDays, dates.size(), asOf);
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    record CapturedRequest(
            long ingredientId,
            String unit,
            int horizonDays,
            List<LocalDate> dates,
            List<BigDecimal> quantities,
            String tokenHeader) {
    }
}
