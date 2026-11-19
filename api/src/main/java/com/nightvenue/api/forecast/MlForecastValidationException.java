package com.nightvenue.api.forecast;

public class MlForecastValidationException extends RuntimeException {
    private final String code;

    public MlForecastValidationException(String code) {
        super("Forecast request rejected");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
