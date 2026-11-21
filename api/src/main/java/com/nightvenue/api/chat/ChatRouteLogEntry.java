package com.nightvenue.api.chat;

import java.math.BigDecimal;
import java.time.Instant;

public record ChatRouteLogEntry(
        Long venueId,
        String route,
        String intent,
        BigDecimal confidence,
        boolean mlAvailable,
        Integer classifierMs,
        String llmProvider,
        Integer llmMs,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal estimatedCostUsd,
        Instant createdAt) {
}
