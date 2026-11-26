package com.nightvenue.api.chat;

import java.math.BigDecimal;

public record ChatClassification(ChatIntent intent, BigDecimal confidence, String modelVersion) {
}
