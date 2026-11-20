package com.nightvenue.api.forecast;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ForecastTestSupport.ClockConfiguration.class})
class ForecastIntegrationTests extends ForecastTestSupport {
    private static final ConfigurableMlStub STUB = startStub();

    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry registry) {
        registry.add("nightvenue.ml.service-url", STUB::url);
        registry.add("nightvenue.ml.internal-token", () -> INTERNAL_TOKEN);
        registry.add("nightvenue.ml.forecast-timeout-ms", () -> "1000");
    }

    @AfterAll
    static void closeStub() {
        STUB.close();
    }

    @Override
    ConfigurableMlStub stub() {
        return STUB;
    }

    @Test
    void adminSuccessPersistsResponseAndRequestingUser() throws Exception {
        addHistory(DEFAULT_AS_OF.minusDays(27), 28, null, null);

        var result = postForecast("ADMIN", venueId, ingredientId, 7);

        assertEquals(200, result.getResponse().getStatus());
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(venueId, response.get("venueId").longValue());
        assertEquals(ingredientId, response.get("ingredientId").longValue());
        assertTrue(response.get("forecastId").longValue() > 0);
        assertEquals("ML", response.get("unit").stringValue());
        assertEquals(7, response.get("horizonDays").intValue());
        assertEquals(0, new BigDecimal("12.500").compareTo(response.get("predictedQuantity").decimalValue()));
        assertEquals("REGRESSION", response.get("selectedModel").stringValue());
        assertEquals("ols_weekday_trend", response.get("modelName").stringValue());
        assertEquals(0, new BigDecimal("1.250").compareTo(response.get("modelMae").decimalValue()));
        assertEquals(0, new BigDecimal("2.500").compareTo(response.get("baselineMae").decimalValue()));
        assertEquals(28, response.get("inputDays").intValue());
        assertEquals(DEFAULT_AS_OF.toString(), response.get("asOfBusinessDate").stringValue());
        assertTrue(response.get("generatedAt").isString());
        assertEquals(1, forecastCount());
        assertEquals(adminId, storedLong("requested_by"));
        assertEquals("ols_weekday_trend", storedString("model_name"));
        assertEquals(DEFAULT_AS_OF, storedDate("as_of_business_date"));
    }

    @Test
    void managerCanCreateForecast() throws Exception {
        addHistory(DEFAULT_AS_OF.minusDays(27), 28, null, null);

        var result = postForecast("MANAGER", venueId, ingredientId, 7);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, forecastCount());
        assertEquals(managerId, storedLong("requested_by"));
    }

    @Test
    void cashierIsForbiddenBeforeCallingMl() throws Exception {
        addHistory(DEFAULT_AS_OF.minusDays(27), 28, null, null);

        var result = postForecast("CASHIER", venueId, ingredientId, 7);

        assertEquals(403, result.getResponse().getStatus());
        assertEquals(0, STUB.requestCount());
        assertNoForecasts();
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        var result = mvc.perform(post("/api/v1/forecasts")
                        .contentType("application/json")
                        .content(body(venueId, ingredientId, 7)))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertEquals(0, STUB.requestCount());
        assertNoForecasts();
    }

    @Test
    void managerCannotRequestAnotherVenue() throws Exception {
        addHistory(DEFAULT_AS_OF.minusDays(27), 28, null, null);

        var result = postForecast("MANAGER", otherVenueId, ingredientId, 7);

        assertEquals(404, result.getResponse().getStatus());
        assertEquals(0, STUB.requestCount());
        assertNoForecasts();
    }

    @Test
    void ingredientStockedOnlyAtOtherVenueIsNotFound() throws Exception {
        var result = postForecast("MANAGER", venueId, otherVenueIngredientId, 7);

        assertEquals(404, result.getResponse().getStatus());
        assertEquals(0, STUB.requestCount());
        assertNoForecasts();
    }

    @Test
    void cutoffChangesAsOfAndPersistedDateAtSix() throws Exception {
        LocalDate firstAsOf = LocalDate.of(2026, 10, 21);
        addHistory(firstAsOf.minusDays(27), 29, null, null);

        clock.set(Instant.parse("2026-10-22T22:59:00Z"));
        var beforeCutoff = postForecast("MANAGER", venueId, ingredientId, 7);
        clock.set(Instant.parse("2026-10-22T23:00:00Z"));
        var atCutoff = postForecast("MANAGER", venueId, ingredientId, 7);

        assertEquals(200, beforeCutoff.getResponse().getStatus());
        assertEquals(200, atCutoff.getResponse().getStatus());
        JsonNode before = objectMapper.readTree(beforeCutoff.getResponse().getContentAsString());
        JsonNode at = objectMapper.readTree(atCutoff.getResponse().getContentAsString());
        assertEquals(firstAsOf.toString(), before.get("asOfBusinessDate").stringValue());
        assertEquals(firstAsOf.plusDays(1).toString(), at.get("asOfBusinessDate").stringValue());
        assertEquals(2, forecastCount());
        List<LocalDate> storedDates = jdbc.query(
                "SELECT as_of_business_date FROM stock_forecasts WHERE venue_id = ? ORDER BY id",
                (rs, row) -> rs.getObject("as_of_business_date", LocalDate.class), venueId);
        assertEquals(List.of(firstAsOf, firstAsOf.plusDays(1)), storedDates);
    }

    @Test
    void noHistoryReturns422WithoutCallingMlOrPersisting() throws Exception {
        var result = postForecast("MANAGER", venueId, ingredientId, 7);

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(0, STUB.requestCount());
        assertNoForecasts();
    }

    @Test
    void twentySevenDaysFailsAndTwentyEightDaysSucceeds() throws Exception {
        addHistory(DEFAULT_AS_OF.minusDays(26), 27, null, null);
        var insufficient = postForecast("MANAGER", venueId, ingredientId, 7);
        addSingleHistoryDay(DEFAULT_AS_OF.minusDays(27));
        var sufficient = postForecast("MANAGER", venueId, ingredientId, 7);

        assertEquals(422, insufficient.getResponse().getStatus());
        assertEquals(200, sufficient.getResponse().getStatus());
        assertEquals(1, STUB.requestCount());
        assertEquals(1, forecastCount());
    }

    @Test
    void capturedSeriesZeroFillsGapAndClampsNegativeConsumption() throws Exception {
        LocalDate start = DEFAULT_AS_OF.minusDays(29);
        LocalDate gapDate = start.plusDays(4);
        LocalDate negativeDate = start.plusDays(9);
        addHistory(start, 30, gapDate, negativeDate);

        var result = postForecast("MANAGER", venueId, ingredientId, 7);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, forecastCount());
        assertEquals(1, STUB.requestCount());
        var captured = STUB.requests().get(0);
        assertEquals(30, captured.dates().size());
        for (int i = 1; i < captured.dates().size(); i++) {
            assertEquals(captured.dates().get(i - 1).plusDays(1), captured.dates().get(i));
        }
        assertEquals(BigDecimal.ZERO, captured.quantities().get(4));
        assertEquals(BigDecimal.ZERO, captured.quantities().get(9));
    }

    @Test
    void mismatchedIngredientResponseIsUnavailableWithoutPersistingOrLeakingToken() throws Exception {
        addHistory(DEFAULT_AS_OF.minusDays(27), 28, null, null);
        STUB.overrideIngredientId(ingredientId + 100_000);

        var result = postForecast("MANAGER", venueId, ingredientId, 7);

        assertEquals(503, result.getResponse().getStatus());
        assertFalse(result.getResponse().getContentAsString().contains(INTERNAL_TOKEN));
        assertEquals(1, STUB.requestCount());
        assertNoForecasts();
    }

    @Test
    void validatesHorizonAndDefaultsWhenOmitted() throws Exception {
        addHistory(DEFAULT_AS_OF.minusDays(27), 28, null, null);
        var zero = postForecast("MANAGER", venueId, ingredientId, 0);
        var tooLarge = postForecast("MANAGER", venueId, ingredientId, 15);
        var defaulted = postForecast("MANAGER", venueId, ingredientId, null);

        assertEquals(400, zero.getResponse().getStatus());
        assertEquals(400, tooLarge.getResponse().getStatus());
        assertEquals(200, defaulted.getResponse().getStatus());
        assertEquals(7, objectMapper.readTree(defaulted.getResponse().getContentAsString())
                .get("horizonDays").intValue());
        assertEquals(1, STUB.requestCount());
        assertEquals(1, forecastCount());
        assertEquals(7, jdbc.queryForObject(
                "SELECT horizon_days FROM stock_forecasts WHERE venue_id = ?", Integer.class, venueId));
    }

    @Test
    void consecutiveSuccessesCreateSeparateRows() throws Exception {
        addHistory(DEFAULT_AS_OF.minusDays(27), 28, null, null);
        var first = postForecast("MANAGER", venueId, ingredientId, 7);
        var second = postForecast("MANAGER", venueId, ingredientId, 7);

        assertEquals(200, first.getResponse().getStatus());
        assertEquals(200, second.getResponse().getStatus());
        assertEquals(2, forecastCount());
        assertEquals(2, STUB.requestCount());
    }

    private long storedLong(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM stock_forecasts WHERE venue_id = ?",
                Long.class, venueId);
    }

    private String storedString(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM stock_forecasts WHERE venue_id = ?",
                String.class, venueId);
    }

    private LocalDate storedDate(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM stock_forecasts WHERE venue_id = ?",
                LocalDate.class, venueId);
    }

    private static ConfigurableMlStub startStub() {
        try {
            return new ConfigurableMlStub();
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
