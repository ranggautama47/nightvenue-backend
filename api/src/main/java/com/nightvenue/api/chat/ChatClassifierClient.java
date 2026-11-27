package com.nightvenue.api.chat;

import com.nightvenue.api.forecast.MlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@EnableConfigurationProperties(ChatProperties.class)
public class ChatClassifierClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatClassifierClient.class);
    private final MlProperties mlProperties;
    private final int timeoutMs;
    private final Semaphore bulkhead;
    private final HttpClient httpClient;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public ChatClassifierClient(MlProperties mlProperties, ChatProperties chatProperties) {
        if (chatProperties.getClassifyMaxConcurrency() < 1) {
            throw new IllegalStateException("chat classifier max concurrency must be at least 1");
        }
        this.mlProperties = mlProperties;
        this.timeoutMs = chatProperties.getClassifyTimeoutMs() > 0
                ? chatProperties.getClassifyTimeoutMs() : 2000;
        this.bulkhead = new Semaphore(chatProperties.getClassifyMaxConcurrency());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public ChatClassifierOutcome classify(String message) {
        long started = System.nanoTime();
        String reason = null;
        String status = "n/a";
        boolean acquired = false;
        ChatClassification classification = null;
        try {
            if (blank(mlProperties.getServiceUrl()) || blank(mlProperties.getInternalToken())) {
                reason = "NOT_CONFIGURED";
            } else if (!(acquired = bulkhead.tryAcquire())) {
                reason = "BUSY";
            } else {
                String url = mlProperties.getServiceUrl().replaceAll("/+$", "") + "/ml/chat/classify";
                String body = mapper.writeValueAsString(Map.of("message", message));
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("X-Internal-Token", mlProperties.getInternalToken())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                var future = httpClient.sendAsync(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                try {
                    HttpResponse<String> response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                    status = Integer.toString(response.statusCode());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        reason = "HTTP_STATUS";
                    } else {
                        try {
                            classification = parseResponse(response.body());
                            if (classification == null) reason = "INVALID_RESPONSE";
                        } catch (RuntimeException exception) {
                            reason = "INVALID_RESPONSE";
                        }
                    }
                } catch (TimeoutException exception) {
                    future.cancel(true);
                    reason = "TIMEOUT";
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    reason = "INTERRUPTED";
                } catch (ExecutionException exception) {
                    reason = "CONNECT_OR_IO";
                }
            }
        } catch (RuntimeException exception) {
            reason = "CONNECT_OR_IO";
        } finally {
            if (acquired) bulkhead.release();
        }
        if (reason != null) {
            LOGGER.warn("Chat classifier unavailable reason={} status={}", reason, status);
        }
        return new ChatClassifierOutcome(classification, elapsedMs(started));
    }

    private ChatClassification parseResponse(String body) {
        JsonNode root = mapper.readTree(body);
        if (root == null || !root.isObject()) return null;
        JsonNode intentNode = root.get("intent");
        JsonNode confidenceNode = root.get("confidence");
        JsonNode versionNode = root.get("modelVersion");
        if (intentNode == null || !intentNode.isString()
                || confidenceNode == null || !confidenceNode.isNumber()
                || versionNode == null || !versionNode.isString() || versionNode.stringValue().isBlank()) {
            return null;
        }
        var intent = ChatIntent.parse(intentNode.stringValue());
        BigDecimal confidence = confidenceNode.decimalValue();
        if (intent.isEmpty() || confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0) return null;
        return new ChatClassification(intent.get(), confidence, versionNode.stringValue());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static int elapsedMs(long started) {
        long elapsed = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        return (int) Math.min(Integer.MAX_VALUE, elapsed);
    }
}
