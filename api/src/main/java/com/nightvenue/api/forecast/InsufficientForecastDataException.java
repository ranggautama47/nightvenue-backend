package com.nightvenue.api.forecast;

public final class InsufficientForecastDataException extends RuntimeException {
    private final int availableDays;
    private final int requiredDays;

    public InsufficientForecastDataException(int availableDays, int requiredDays) {
        super("Insufficient forecast data: available=" + availableDays
                + " required=" + requiredDays);
        this.availableDays = availableDays;
        this.requiredDays = requiredDays;
    }

    public int availableDays() {
        return availableDays;
    }

    public int requiredDays() {
        return requiredDays;
    }
}
