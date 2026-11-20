package com.nightvenue.api.forecast;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ForecastTestSupport.ClockConfiguration.class})
class ForecastMlValidationIntegrationTests extends ForecastMlFailureTestSupport {
    @Test
    void upstreamInsufficientDataReturns422FromMl() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.VALIDATION_422_INSUFFICIENT)) {
            pointAt(stub);
            seedSufficientHistory();

            var result = postForecast("MANAGER", venueId, ingredientId, 7);

            assertFailure(result, 422);
            assertRequestCaptured(stub);
            assertTrue(detail(result).contains("INSUFFICIENT_DATA"));
            assertFalse(detail(result).contains("available="));
        }
    }

    @Test
    void upstreamInvalidSeriesReturns422() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.VALIDATION_422_INVALID_SERIES)) {
            pointAt(stub);
            seedSufficientHistory();

            var result = postForecast("MANAGER", venueId, ingredientId, 7);

            assertFailure(result, 422);
            assertRequestCaptured(stub);
            assertTrue(detail(result).contains("INVALID_SERIES"));
        }
    }
}
