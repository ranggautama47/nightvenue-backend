package com.nightvenue.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MlForecastResponse(
        Long ingredientId,
        String unit,
        Integer horizonDays,
        BigDecimal predictedQuantity,
        String selectedModel,
        String modelName,
        BigDecimal modelMae,
        BigDecimal baselineMae,
        Integer inputDays,
        LocalDate asOfBusinessDate,
        String regressionModelName,
        String baselineModelName) {
}
