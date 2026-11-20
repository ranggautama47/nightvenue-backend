package com.nightvenue.api.forecast;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.InvalidRequest;
import com.nightvenue.api.common.ResourceNotFound;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

@Service
public class ForecastService {
    private final ForecastRepository forecasts;
    private final MlClient mlClient;
    private final BusinessDateService businessDates;
    private final Clock clock;
    private final ForecastSeriesBuilder seriesBuilder = new ForecastSeriesBuilder();

    public ForecastService(
            ForecastRepository forecasts,
            MlClient mlClient,
            BusinessDateService businessDates,
            Clock clock) {
        this.forecasts = forecasts;
        this.mlClient = mlClient;
        this.businessDates = businessDates;
        this.clock = clock;
    }

    public ForecastResult forecast(long venueId, long ingredientId, Integer requestedHorizon, long requestedBy) {
        int horizon = requestedHorizon == null ? 7 : requestedHorizon;
        if (horizon < 1 || horizon > 14) {
            throw new InvalidRequest("horizonDays must be between 1 and 14");
        }
        if (!forecasts.isIngredientInVenue(venueId, ingredientId)) {
            throw new ResourceNotFound();
        }
        ForecastRepository.VenueContext venue = forecasts.findVenueContext(venueId)
                .orElseThrow(VenueNotFound::new);
        String unit = forecasts.findIngredientUnit(ingredientId).orElseThrow(ResourceNotFound::new);
        LocalDate asOf = businessDates.currentBusinessDate(
                ZoneId.of(venue.timezone()), venue.cutoffHour()).minusDays(1);
        var ledger = forecasts.findDailyNetConsumption(
                venueId, ingredientId, asOf.minusDays(89), asOf);
        ForecastSeriesBuilder.SeriesResult built;
        try {
            built = seriesBuilder.build(asOf, horizon, ledger);
        } catch (InsufficientForecastDataException exception) {
            throw new BusinessRuleViolation("INSUFFICIENT_DATA: available="
                    + exception.availableDays() + " required=" + exception.requiredDays());
        }

        MlForecastResponse response;
        try {
            response = mlClient.forecast(new MlForecastRequest(
                    ingredientId, unit, horizon, built.series()));
        } catch (MlForecastValidationException exception) {
            throw new BusinessRuleViolation(exception.getCode());
        }
        if (response == null
                || !Objects.equals(response.ingredientId(), ingredientId)
                || !Objects.equals(response.unit(), unit)
                || !Objects.equals(response.horizonDays(), horizon)
                || !Objects.equals(response.inputDays(), built.inputDays())
                || !Objects.equals(response.asOfBusinessDate(), asOf)
                || response.predictedQuantity() == null
                || response.modelName() == null
                || response.modelMae() == null
                || response.baselineMae() == null) {
            throw new MlServiceUnavailable();
        }

        var generatedAt = clock.instant();
        long forecastId = forecasts.insertForecast(new ForecastRepository.NewForecast(
                venueId, ingredientId, generatedAt, asOf, horizon,
                response.predictedQuantity(), response.modelName(), response.modelMae(),
                response.baselineMae(), built.inputDays(), requestedBy));
        return new ForecastResult(forecastId, venueId, ingredientId, unit, horizon,
                response.predictedQuantity(), response.selectedModel(), response.modelName(),
                response.modelMae(), response.baselineMae(), built.inputDays(), asOf, generatedAt);
    }

    public record ForecastResult(
            long forecastId,
            long venueId,
            long ingredientId,
            String unit,
            int horizonDays,
            BigDecimal predictedQuantity,
            String selectedModel,
            String modelName,
            BigDecimal modelMae,
            BigDecimal baselineMae,
            int inputDays,
            LocalDate asOfBusinessDate,
            Instant generatedAt) {
    }
}
