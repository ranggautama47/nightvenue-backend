package com.nightvenue.api.forecast;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
    void delayedResponseBodyTimesOutWithoutBreakingAuth() throws Exception {
        ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
        try (MlStub stub = new MlStub(MlStub.Mode.DELAYED_BODY)) {
            pointAt(stub);
            seedSufficientHistory();

            var pendingForecast = requestExecutor.submit(
                    () -> postForecast("MANAGER", venueId, ingredientId, 7));
            assertTrue(stub.awaitBodyStarted(2, TimeUnit.SECONDS));

            var result = pendingForecast.get(3, TimeUnit.SECONDS);
            assertFailure(result, 503);
            assertRequestCaptured(stub);
            assertTrue(stub.bodyIsHeld());

            var authResult = mvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", bearer("MANAGER")))
                    .andReturn();
            assertEquals(200, authResult.getResponse().getStatus());
        } finally {
            requestExecutor.shutdownNow();
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
