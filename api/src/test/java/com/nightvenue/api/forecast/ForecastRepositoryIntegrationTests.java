package com.nightvenue.api.forecast;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ForecastRepositoryIntegrationTests {
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 10, 1);
    private static final Instant GENERATED_AT = Instant.parse("2026-10-22T10:15:30Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired ForecastRepository forecasts;

    private String fixture;
    private long venueId;
    private long otherVenueId;
    private long ingredientId;
    private long warehouseId;
    private long barId;
    private long otherVenueLocationId;
    private long warehouseItemId;
    private long barItemId;
    private long otherVenueItemId;
    private long userId;

    @BeforeEach
    void createFixtures() {
        fixture = "F9A-" + UUID.randomUUID().toString().substring(0, 8);
        venueId = createVenue(fixture + "-a");
        otherVenueId = createVenue(fixture + "-b");
        warehouseId = createLocation(venueId, fixture + "-wh", "WAREHOUSE");
        barId = createLocation(venueId, fixture + "-bar", "BAR");
        otherVenueLocationId = createLocation(otherVenueId, fixture + "-other", "BAR");
        ingredientId = createIngredient("ML");
        warehouseItemId = createItem(warehouseId, ingredientId);
        barItemId = createItem(barId, ingredientId);
        otherVenueItemId = createItem(otherVenueLocationId, ingredientId);
        userId = createUser();
    }

    @AfterEach
    void cleanFixtures() {
        if (fixture == null) return;
        jdbc.update("DELETE FROM stock_forecasts WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("""
                DELETE FROM inventory_transactions WHERE inventory_item_id IN (
                    SELECT i.id FROM inventory_items i
                    JOIN stock_locations l ON l.id = i.location_id WHERE l.venue_id IN (?, ?)
                )
                """, venueId, otherVenueId);
        jdbc.update("DELETE FROM inventory_items WHERE location_id IN (?, ?, ?)",
                warehouseId, barId, otherVenueLocationId);
        jdbc.update("DELETE FROM stock_locations WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM ingredients WHERE sku LIKE ?", fixture + "-%");
        jdbc.update("DELETE FROM users WHERE email = ?", fixture.toLowerCase() + "@example.test");
        jdbc.update("DELETE FROM venues WHERE id IN (?, ?)", venueId, otherVenueId);
    }

    @Test
    void detectsIngredientStockedInVenueAndRejectsOtherVenueOnlyIngredient() {
        long onlyOtherVenueIngredient = createIngredient("PIECE");
        createItem(otherVenueLocationId, onlyOtherVenueIngredient);

        assertTrue(forecasts.isIngredientInVenue(venueId, ingredientId));
        assertFalse(forecasts.isIngredientInVenue(venueId, onlyOtherVenueIngredient));
    }

    @Test
    void sumsLocationsAndExcludesOtherVenue() {
        ledger(warehouseItemId, "SALE_DEDUCT", "-5", BASE_DATE, GENERATED_AT);
        ledger(barItemId, "SALE_DEDUCT", "-4", BASE_DATE, GENERATED_AT);
        ledger(otherVenueItemId, "SALE_DEDUCT", "-90", BASE_DATE, GENERATED_AT);

        List<ForecastRepository.LedgerDay> result = days(BASE_DATE, BASE_DATE);
        assertEquals(1, result.size());
        assertEquals(new BigDecimal("9.000"), result.get(0).netConsumption());
    }

    @Test
    void saleAndVoidReturnKeepSignedRawNetConsumptionOnStoredBusinessDate() {
        LocalDate saleDate = BASE_DATE.plusDays(1);
        LocalDate returnDate = BASE_DATE.plusDays(2);
        ledger(warehouseItemId, "SALE_DEDUCT", "-5", saleDate, GENERATED_AT);
        ledger(warehouseItemId, "VOID_RETURN", "2", returnDate, GENERATED_AT);

        List<ForecastRepository.LedgerDay> result = days(saleDate, returnDate);
        assertEquals(new BigDecimal("5.000"), result.get(0).netConsumption());
        assertEquals(new BigDecimal("-2.000"), result.get(1).netConsumption());
        assertNotEquals(result.get(1).businessDate(), GENERATED_AT.atZone(java.time.ZoneOffset.UTC).toLocalDate());

        LocalDate sameDay = returnDate.plusDays(1);
        ledger(barItemId, "SALE_DEDUCT", "-5", sameDay, GENERATED_AT);
        ledger(barItemId, "VOID_RETURN", "2", sameDay, GENERATED_AT);
        assertEquals(new BigDecimal("3.000"), days(sameDay, sameDay).get(0).netConsumption());
    }

    @Test
    void excludesNonConsumptionTypesAndReturnsAscendingPresentDaysWithinInclusiveRange() {
        LocalDate before = BASE_DATE.minusDays(1);
        LocalDate first = BASE_DATE;
        LocalDate last = BASE_DATE.plusDays(3);
        LocalDate after = BASE_DATE.plusDays(4);
        ledger(warehouseItemId, "SALE_DEDUCT", "-1", before, GENERATED_AT);
        ledger(warehouseItemId, "SALE_DEDUCT", "-1", first, GENERATED_AT);
        ledger(warehouseItemId, "PURCHASE_IN", "-100", first, GENERATED_AT);
        ledger(warehouseItemId, "WASTE", "-20", first.plusDays(1), GENERATED_AT);
        ledger(warehouseItemId, "ADJUSTMENT", "-30", first.plusDays(2), GENERATED_AT);
        ledger(warehouseItemId, "SALE_DEDUCT", "-2", last, GENERATED_AT);
        ledger(warehouseItemId, "SALE_DEDUCT", "-3", after, GENERATED_AT);

        List<ForecastRepository.LedgerDay> result = days(first, last);
        assertEquals(List.of(first, last), result.stream().map(ForecastRepository.LedgerDay::businessDate).toList());
        assertEquals(new BigDecimal("1.000"), result.get(0).netConsumption());
        assertEquals(new BigDecimal("2.000"), result.get(1).netConsumption());
    }

    @Test
    void insertsForecastAndPersistsNumericValuesAndNullMae() {
        long id = forecasts.insertForecast(newForecast(
                new BigDecimal("123.457"), new BigDecimal("0.001"), null, 28));
        assertTrue(id > 0);
        assertEquals(0, jdbc.queryForObject(
                "SELECT predicted_quantity FROM stock_forecasts WHERE id = ?", BigDecimal.class, id)
                .compareTo(new BigDecimal("123.457")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT model_mae FROM stock_forecasts WHERE id = ?", BigDecimal.class, id)
                .compareTo(new BigDecimal("0.001")));
        assertNull(jdbc.queryForObject(
                "SELECT baseline_mae FROM stock_forecasts WHERE id = ?", BigDecimal.class, id));
    }

    @Test
    void databaseRejectsInvalidForecastChecksAndRestrictsReferencedDeletes() {
        DataIntegrityViolationException inputDaysViolation = assertThrows(
                DataIntegrityViolationException.class, () -> forecasts.insertForecast(
                        newForecast(new BigDecimal("1"), null, null, 27)));
        assertConstraintViolation(inputDaysViolation, "ck_stock_forecasts_input_days_min");

        DataIntegrityViolationException predictedQuantityViolation = assertThrows(
                DataIntegrityViolationException.class, () -> forecasts.insertForecast(
                        newForecast(new BigDecimal("-1"), null, null, 28)));
        assertConstraintViolation(predictedQuantityViolation, "ck_stock_forecasts_predicted_nonneg");

        DataIntegrityViolationException horizonViolation = assertThrows(
                DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO stock_forecasts
                    (venue_id, ingredient_id, generated_at, as_of_business_date, horizon_days,
                     predicted_quantity, model_name, input_days, requested_by)
                VALUES (?, ?, ?, ?, 0, 1, 'test', 28, ?)
                """, venueId, ingredientId, Timestamp.from(GENERATED_AT), BASE_DATE, userId));
        assertConstraintViolation(horizonViolation, "ck_stock_forecasts_horizon_positive");

        long forecastId = forecasts.insertForecast(newForecast(new BigDecimal("1"), null, null, 28));
        assertTrue(forecastId > 0);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM venues WHERE id = ?", venueId));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM ingredients WHERE id = ?", ingredientId));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM users WHERE id = ?", userId));
    }

    private void assertConstraintViolation(
            DataIntegrityViolationException exception, String constraintName) {
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        assertTrue(stackTrace.toString().contains(constraintName),
                () -> "Expected stack trace to contain " + constraintName);
    }

    private List<ForecastRepository.LedgerDay> days(LocalDate from, LocalDate to) {
        return forecasts.findDailyNetConsumption(venueId, ingredientId, from, to);
    }

    private void ledger(long itemId, String type, String delta, LocalDate businessDate, Instant createdAt) {
        jdbc.update("""
                INSERT INTO inventory_transactions
                    (inventory_item_id, type, delta, balance_after, business_date, reference_type,
                     reference_id, reason, created_by, created_at)
                VALUES (?, ?, ?, 100, ?, 'MANUAL', NULL, ?, ?, ?)
                """, itemId, type, new BigDecimal(delta), businessDate,
                type.equals("WASTE") || type.equals("ADJUSTMENT") ? "fixture" : null,
                userId, java.sql.Timestamp.from(createdAt));
    }

    private ForecastRepository.NewForecast newForecast(
            BigDecimal quantity, BigDecimal mae, BigDecimal baselineMae, int inputDays) {
        return new ForecastRepository.NewForecast(venueId, ingredientId, GENERATED_AT, BASE_DATE,
                7, quantity, "ols_weekday_trend", mae, baselineMae, inputDays, userId);
    }

    private long createVenue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, is_active, created_at, updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, code, code);
    }

    private long createLocation(long venueId, String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO stock_locations (venue_id, name, type) VALUES (?, ?, ?) RETURNING id
                """, Long.class, venueId, name, type);
    }

    private long createIngredient(String unit) {
        String sku = fixture + "-" + UUID.randomUUID().toString().substring(0, 6);
        return jdbc.queryForObject("""
                INSERT INTO ingredients (sku, name, unit, is_active) VALUES (?, ?, ?, true) RETURNING id
                """, Long.class, sku, sku, unit);
    }

    private long createItem(long locationId, long ingredientId) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version, updated_at)
                VALUES (?, ?, 0, 0, 0, now()) RETURNING id
                """, Long.class, locationId, ingredientId);
    }

    private long createUser() {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, version, created_at, updated_at)
                VALUES (?, 'fixture-hash', 'Forecast Fixture', 'MANAGER', 0, now(), now()) RETURNING id
                """, Long.class, fixture.toLowerCase() + "@example.test");
    }
}
