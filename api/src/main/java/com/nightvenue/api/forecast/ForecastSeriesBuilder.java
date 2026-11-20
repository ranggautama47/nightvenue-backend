package com.nightvenue.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

public final class ForecastSeriesBuilder {
    public static final int MIN_DAYS = 28;
    public static final int MAX_WINDOW_DAYS = 90;
    public static final int MIN_HORIZON = 1;
    public static final int MAX_HORIZON = 14;

    public record SeriesResult(List<MlForecastRequest.Observation> series, int inputDays) {
    }

    public SeriesResult build(
            LocalDate asOfBusinessDate,
            int horizonDays,
            List<ForecastRepository.LedgerDay> ledger) {
        Objects.requireNonNull(asOfBusinessDate, "asOfBusinessDate");
        Objects.requireNonNull(ledger, "ledger");
        if (horizonDays < MIN_HORIZON || horizonDays > MAX_HORIZON) {
            throw new IllegalArgumentException("horizonDays must be between 1 and 14");
        }

        int requiredDays = Math.max(MIN_DAYS, 21 + horizonDays);
        TreeMap<LocalDate, BigDecimal> quantitiesByDate = new TreeMap<>();
        for (ForecastRepository.LedgerDay row : ledger) {
            Objects.requireNonNull(row, "ledger element");
            LocalDate date = row.businessDate();
            if (!date.isAfter(asOfBusinessDate)) {
                quantitiesByDate.merge(date, row.netConsumption(), BigDecimal::add);
            }
        }
        if (quantitiesByDate.isEmpty()) {
            throw new InsufficientForecastDataException(0, requiredDays);
        }

        LocalDate windowStart = asOfBusinessDate.minusDays(MAX_WINDOW_DAYS - 1L);
        if (quantitiesByDate.firstKey().isAfter(windowStart)) {
            windowStart = quantitiesByDate.firstKey();
        }
        quantitiesByDate.headMap(windowStart, false).clear();

        int spanDays = Math.toIntExact(ChronoUnit.DAYS.between(windowStart, asOfBusinessDate) + 1);
        if (spanDays < requiredDays) {
            throw new InsufficientForecastDataException(spanDays, requiredDays);
        }

        List<MlForecastRequest.Observation> series = new ArrayList<>(spanDays);
        for (LocalDate date = windowStart; !date.isAfter(asOfBusinessDate); date = date.plusDays(1)) {
            BigDecimal quantity = quantitiesByDate.get(date);
            if (quantity == null || quantity.signum() < 0) {
                quantity = BigDecimal.ZERO;
            }
            series.add(new MlForecastRequest.Observation(date, quantity));
        }
        List<MlForecastRequest.Observation> result = List.copyOf(series);
        return new SeriesResult(result, result.size());
    }
}
