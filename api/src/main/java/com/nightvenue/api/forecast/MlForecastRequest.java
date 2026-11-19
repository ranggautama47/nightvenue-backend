package com.nightvenue.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record MlForecastRequest(
        long ingredientId,
        String unit,
        int horizonDays,
        List<Observation> series) {

    public record Observation(LocalDate businessDate, BigDecimal quantity) {
    }
}
