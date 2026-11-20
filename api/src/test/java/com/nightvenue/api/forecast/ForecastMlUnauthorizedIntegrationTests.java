package com.nightvenue.api.forecast;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ForecastTestSupport.ClockConfiguration.class})
class ForecastMlUnauthorizedIntegrationTests extends ForecastMlFailureTestSupport {
    @Test
    void upstream401Returns503NotUnauthorized() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.UNAUTHORIZED)) {
            pointAt(stub);
            seedSufficientHistory();

            var result = postForecast("MANAGER", venueId, ingredientId, 7);

            assertFailure(result, 503);
            assertRequestCaptured(stub);
        }
    }

    @Test
    void upstream404Returns503() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.NOT_FOUND_404)) {
            pointAt(stub);
            seedSufficientHistory();

            var result = postForecast("MANAGER", venueId, ingredientId, 7);

            assertFailure(result, 503);
            assertRequestCaptured(stub);
        }
    }
}
