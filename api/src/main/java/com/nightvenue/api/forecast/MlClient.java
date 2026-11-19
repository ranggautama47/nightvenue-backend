package com.nightvenue.api.forecast;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@EnableConfigurationProperties(MlProperties.class)
public class MlClient {
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final String FORECAST_PATH = "/ml/forecast/inventory";

    private final MlProperties properties;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public MlClient(MlProperties properties) {
        this.properties = properties;
        int timeoutMs = properties.getForecastTimeoutMs() > 0
                ? properties.getForecastTimeoutMs() : DEFAULT_TIMEOUT_MS;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // Read timeout bounds waiting for response headers per request; it is not a total body deadline.
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public MlForecastResponse forecast(MlForecastRequest request) {
        if (blank(properties.getServiceUrl()) || blank(properties.getInternalToken())) {
            throw new MlServiceUnavailable();
        }
        try {
            String url = properties.getServiceUrl().replaceAll("/+$", "") + FORECAST_PATH;
            String body = restClient.post()
                    .uri(url)
                    .header("X-Internal-Token", properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestJson(request))
                    .exchange((httpRequest, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 422) {
                            throw validationOrUnavailable(readBody(response.getBody().readAllBytes()));
                        }
                        if (status < 200 || status >= 300) {
                            throw new MlServiceUnavailable();
                        }
                        return readBody(response.getBody().readAllBytes());
                    });
            return parseResponse(body);
        } catch (MlForecastValidationException | MlServiceUnavailable exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new MlServiceUnavailable();
        } catch (RuntimeException exception) {
            throw new MlServiceUnavailable();
        }
    }

    private String requestJson(MlForecastRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ingredientId", request.ingredientId());
        json.put("unit", request.unit());
        json.put("horizonDays", request.horizonDays());
        List<Map<String, Object>> series = new ArrayList<>();
        for (MlForecastRequest.Observation observation : request.series()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("businessDate", observation.businessDate().toString());
            row.put("quantity", observation.quantity());
            series.add(row);
        }
        json.put("series", series);
        return mapper.writeValueAsString(json);
    }

    private MlForecastResponse parseResponse(String body) {
        JsonNode root = mapper.readTree(body);
        String unit = requiredText(root, "unit");
        String selectedModel = requiredText(root, "selectedModel");
        if (!List.of("ML", "GRAM", "PIECE").contains(unit)
                || !List.of("REGRESSION", "BASELINE").contains(selectedModel)) {
            throw new MlServiceUnavailable();
        }
        return new MlForecastResponse(
                requiredLong(root, "ingredientId"),
                unit,
                requiredInteger(root, "horizonDays"),
                requiredDecimal(root, "predictedQuantity"),
                selectedModel,
                requiredText(root, "modelName"),
                requiredDecimal(root, "modelMae"),
                requiredDecimal(root, "baselineMae"),
                requiredInteger(root, "inputDays"),
                LocalDate.parse(requiredText(root, "asOfBusinessDate")),
                requiredText(root, "regressionModelName"),
                requiredText(root, "baselineModelName"));
    }

    private RuntimeException validationOrUnavailable(String body) {
        JsonNode root = mapper.readTree(body);
        JsonNode code = root.get("code");
        if (code != null && code.isString()) {
            if ("INSUFFICIENT_DATA".equals(code.stringValue())
                    || "INVALID_SERIES".equals(code.stringValue())) {
                return new MlForecastValidationException(code.stringValue());
            }
        }
        return new MlServiceUnavailable();
    }

    private static Integer requiredInteger(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new MlServiceUnavailable();
        }
        return value.intValue();
    }

    private static Long requiredLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new MlServiceUnavailable();
        }
        return value.longValue();
    }

    private static BigDecimal requiredDecimal(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isNumber()) {
            throw new MlServiceUnavailable();
        }
        return value.decimalValue();
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MlServiceUnavailable();
        }
        return value.stringValue();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String readBody(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
