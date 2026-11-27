package com.nightvenue.api.chat;

import java.math.BigDecimal;

public record ChatResponse(
        String answer,
        String route,
        String intent,
        BigDecimal confidence,
        String modelVersion
) {
}
