package com.nightvenue.api.forecast;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

abstract class ForecastMlFailureTestSupport extends ForecastTestSupport {
    private static final ConfigurableMlStub UNUSED = startStub();

    @Autowired MlProperties mlProperties;

    private String originalServiceUrl;
    private String originalInternalToken;

    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry registry) {
        registry.add("nightvenue.ml.service-url", UNUSED::url);
        registry.add("nightvenue.ml.internal-token", () -> INTERNAL_TOKEN);
        registry.add("nightvenue.ml.forecast-timeout-ms", () -> "1000");
    }

    @Override
    ConfigurableMlStub stub() {
        return UNUSED;
    }

    @BeforeEach
    void captureMlProperties() {
        originalServiceUrl = mlProperties.getServiceUrl();
        originalInternalToken = mlProperties.getInternalToken();
    }

    @AfterEach
    void restoreMlProperties() {
        mlProperties.setServiceUrl(originalServiceUrl);
        mlProperties.setInternalToken(originalInternalToken);
    }

    void pointAt(MlStub stub) {
        mlProperties.setServiceUrl(stub.url());
        mlProperties.setInternalToken(INTERNAL_TOKEN);
    }

    void seedSufficientHistory() {
        addHistory(DEFAULT_AS_OF.minusDays(27), 28, null, null);
    }

    void assertFailure(MvcResult result, int expectedStatus) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertEquals(expectedStatus, result.getResponse().getStatus());
        assertFalse(body.contains(INTERNAL_TOKEN));
        assertEquals(0, forecastCount());
        if (expectedStatus == 503) {
            assertEquals("Forecast service unavailable", objectMapper.readTree(body).path("detail").stringValue());
        }
    }

    void assertRequestCaptured(MlStub stub) {
        assertEquals(1, stub.requestCount());
        assertEquals(INTERNAL_TOKEN, stub.token);
    }

    String detail(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("detail").stringValue();
    }

    private static ConfigurableMlStub startStub() {
        try {
            return new ConfigurableMlStub();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
