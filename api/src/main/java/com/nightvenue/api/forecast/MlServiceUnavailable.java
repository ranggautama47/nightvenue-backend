package com.nightvenue.api.forecast;

public class MlServiceUnavailable extends RuntimeException {
    public MlServiceUnavailable() {
        super("Forecast service unavailable");
    }
}
