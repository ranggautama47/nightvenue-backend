package com.nightvenue.api.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nightvenue.ml")
public class MlProperties {
    private String serviceUrl = "";
    private String internalToken = "";
    private int forecastTimeoutMs = 10_000;

    public MlProperties() {
    }

    public MlProperties(String serviceUrl, String internalToken, int forecastTimeoutMs) {
        this.serviceUrl = serviceUrl;
        this.internalToken = internalToken;
        this.forecastTimeoutMs = forecastTimeoutMs;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public int getForecastTimeoutMs() {
        return forecastTimeoutMs;
    }

    public void setForecastTimeoutMs(int forecastTimeoutMs) {
        this.forecastTimeoutMs = forecastTimeoutMs;
    }

    @Override
    public String toString() {
        return "MlProperties{serviceUrl=<redacted>, internalToken=<redacted>, forecastTimeoutMs="
                + forecastTimeoutMs + "}";
    }
}
