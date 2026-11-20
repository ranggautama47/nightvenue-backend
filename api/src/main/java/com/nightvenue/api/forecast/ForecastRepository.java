package com.nightvenue.api.forecast;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ForecastRepository {
    private final JdbcTemplate jdbc;

    public ForecastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isIngredientInVenue(long venueId, long ingredientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM inventory_items i
                    JOIN stock_locations l ON l.id = i.location_id
                    WHERE l.venue_id = ? AND i.ingredient_id = ?
                )
                """, Boolean.class, venueId, ingredientId);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<VenueContext> findVenueContext(long venueId) {
        List<VenueContext> contexts = jdbc.query("""
                SELECT timezone, business_day_cutoff_hour
                FROM venues
                WHERE id = ?
                """, (rs, row) -> new VenueContext(
                rs.getString("timezone"), rs.getInt("business_day_cutoff_hour")), venueId);
        return contexts.stream().findFirst();
    }

    public Optional<String> findIngredientUnit(long ingredientId) {
        List<String> units = jdbc.query("SELECT unit FROM ingredients WHERE id = ?",
                (rs, row) -> rs.getString("unit"), ingredientId);
        return units.stream().findFirst();
    }

    public List<LedgerDay> findDailyNetConsumption(
            long venueId, long ingredientId, LocalDate fromInclusive, LocalDate toInclusive) {
        return jdbc.query("""
                SELECT t.business_date, SUM(-t.delta) AS net_consumption
                FROM inventory_transactions t
                JOIN inventory_items i ON i.id = t.inventory_item_id
                JOIN stock_locations l ON l.id = i.location_id
                WHERE l.venue_id = ? AND i.ingredient_id = ?
                  AND t.type IN ('SALE_DEDUCT', 'VOID_RETURN')
                  AND t.business_date BETWEEN ? AND ?
                GROUP BY t.business_date
                ORDER BY t.business_date ASC
                """, (rs, row) -> new LedgerDay(
                rs.getObject("business_date", LocalDate.class), rs.getBigDecimal("net_consumption")),
                venueId, ingredientId, fromInclusive, toInclusive);
    }

    public long insertForecast(NewForecast forecast) {
        return jdbc.queryForObject("""
                INSERT INTO stock_forecasts
                    (venue_id, ingredient_id, generated_at, as_of_business_date, horizon_days,
                     predicted_quantity, model_name, model_mae, baseline_mae, input_days, requested_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, forecast.venueId(), forecast.ingredientId(),
                Timestamp.from(forecast.generatedAt()), forecast.asOfBusinessDate(), forecast.horizonDays(),
                forecast.predictedQuantity(), forecast.modelName(), forecast.modelMae(),
                forecast.baselineMae(), forecast.inputDays(), forecast.requestedBy());
    }

    public record LedgerDay(LocalDate businessDate, BigDecimal netConsumption) {
    }

    public record VenueContext(String timezone, int cutoffHour) {
    }

    public record NewForecast(
            long venueId,
            long ingredientId,
            Instant generatedAt,
            LocalDate asOfBusinessDate,
            int horizonDays,
            BigDecimal predictedQuantity,
            String modelName,
            BigDecimal modelMae,
            BigDecimal baselineMae,
            int inputDays,
            long requestedBy) {
    }
}
