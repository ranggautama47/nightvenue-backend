package com.nightvenue.api.forecast;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ForecastTestSupport.ClockConfiguration.class})
class ForecastMlTimeoutIntegrationTests extends ForecastMlFailureTestSupport {
    @Test
    void hangModeTimesOutAndReturns503() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.HANG)) {
            pointAt(stub);
            seedSufficientHistory();

            var result = postForecast("MANAGER", venueId, ingredientId, 7);

            assertFailure(result, 503);
            assertRequestCaptured(stub);
        }
    }

    @Test
    void connectionRefusedReturns503() throws Exception {
        String deadUrl;
        try (MlStub stub = new MlStub(MlStub.Mode.SUCCESS)) {
            deadUrl = stub.url();
        }
        mlProperties.setServiceUrl(deadUrl);
        mlProperties.setInternalToken(INTERNAL_TOKEN);
        seedSufficientHistory();

        var result = postForecast("MANAGER", venueId, ingredientId, 7);

        assertFailure(result, 503);
    }
}
