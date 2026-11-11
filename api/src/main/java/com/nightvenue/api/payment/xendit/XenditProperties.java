package com.nightvenue.api.payment.xendit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Xendit provider settings. An empty secretKey means the provider is not
 * configured: QRIS create must answer 503 PROVIDER_NOT_CONFIGURED without
 * touching the idempotency table, the payments table, or the network.
 */
@ConfigurationProperties("nightvenue.payment.xendit")
public class XenditProperties {
    private String secretKey = "";
    private String baseUrl = "https://api.xendit.co";
    private String apiVersion = "2024-11-11";
    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 10_000;

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }
}
