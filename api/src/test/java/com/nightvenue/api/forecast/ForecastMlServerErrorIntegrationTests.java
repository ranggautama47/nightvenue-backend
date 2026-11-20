package com.nightvenue.api.forecast;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ForecastTestSupport.ClockConfiguration.class})
class ForecastMlServerErrorIntegrationTests extends ForecastMlFailureTestSupport {
    @Test
    void upstream500Returns503() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.SERVER_500)) {
            pointAt(stub);
            seedSufficientHistory();

            var result = postForecast("MANAGER", venueId, ingredientId, 7);

            assertFailure(result, 503);
            assertRequestCaptured(stub);
        }
    }

    @Test
    void upstreamHtml502Returns503WithoutLeakingBody() throws Exception {
        try (MlStub stub = new MlStub(MlStub.Mode.HTML_502)) {
            pointAt(stub);
            seedSufficientHistory();

            var result = postForecast("MANAGER", venueId, ingredientId, 7);

            assertFailure(result, 503);
            assertRequestCaptured(stub);
            assertFalse(result.getResponse().getContentAsString().contains("private upstream marker"));
        }
    }
}
