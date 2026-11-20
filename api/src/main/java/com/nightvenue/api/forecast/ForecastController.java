package com.nightvenue.api.forecast;

import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.common.InvalidRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/forecasts")
public class ForecastController {
    private final ForecastService forecasts;
    private final VenueAccessService venueAccess;

    public ForecastController(ForecastService forecasts, VenueAccessService venueAccess) {
        this.forecasts = forecasts;
        this.venueAccess = venueAccess;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ForecastResponse forecast(
            @RequestBody ForecastRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (request.venueId() == null || request.ingredientId() == null) {
            throw new InvalidRequest("venueId and ingredientId are required");
        }
        venueAccess.requireAccess(request.venueId());
        ForecastService.ForecastResult result = forecasts.forecast(
                request.venueId(), request.ingredientId(), request.horizonDays(),
                Long.parseLong(jwt.getSubject()));
        return new ForecastResponse(result.forecastId(), result.venueId(), result.ingredientId(),
                result.unit(), result.horizonDays(), result.predictedQuantity(), result.selectedModel(),
                result.modelName(), result.modelMae(), result.baselineMae(), result.inputDays(),
                result.asOfBusinessDate(), result.generatedAt());
    }

    public record ForecastRequest(Long venueId, Long ingredientId, Integer horizonDays) {
    }

    public record ForecastResponse(
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
