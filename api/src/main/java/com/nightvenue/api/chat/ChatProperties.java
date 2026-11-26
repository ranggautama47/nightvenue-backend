package com.nightvenue.api.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties("nightvenue.chat")
public class ChatProperties {
    private int classifyTimeoutMs = 2000;
    private BigDecimal confidenceThreshold;
    private int rateLimitPerMin = 10;
    private int classifyMaxConcurrency = 32;

    public ChatProperties() {
    }

    public ChatProperties(int classifyTimeoutMs, BigDecimal confidenceThreshold,
                          int rateLimitPerMin, int classifyMaxConcurrency) {
        this.classifyTimeoutMs = classifyTimeoutMs;
        this.confidenceThreshold = confidenceThreshold;
        this.rateLimitPerMin = rateLimitPerMin;
        this.classifyMaxConcurrency = classifyMaxConcurrency;
    }

    public int getClassifyTimeoutMs() { return classifyTimeoutMs; }
    public void setClassifyTimeoutMs(int classifyTimeoutMs) { this.classifyTimeoutMs = classifyTimeoutMs; }
    public BigDecimal getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(BigDecimal confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    public int getRateLimitPerMin() { return rateLimitPerMin; }
    public void setRateLimitPerMin(int rateLimitPerMin) { this.rateLimitPerMin = rateLimitPerMin; }
    public int getClassifyMaxConcurrency() { return classifyMaxConcurrency; }
    public void setClassifyMaxConcurrency(int classifyMaxConcurrency) { this.classifyMaxConcurrency = classifyMaxConcurrency; }

    @Override
    public String toString() {
        return "ChatProperties{classifyTimeoutMs=" + classifyTimeoutMs
                + ", confidenceThreshold=" + confidenceThreshold
                + ", rateLimitPerMin=" + rateLimitPerMin
                + ", classifyMaxConcurrency=" + classifyMaxConcurrency + "}";
    }
}
