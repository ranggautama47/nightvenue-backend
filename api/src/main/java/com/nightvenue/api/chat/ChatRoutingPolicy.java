package com.nightvenue.api.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class ChatRoutingPolicy {
    private final BigDecimal threshold;

    @Autowired
    public ChatRoutingPolicy(ChatProperties properties) {
        this(properties.getConfidenceThreshold());
    }

    public ChatRoutingPolicy(BigDecimal threshold) {
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0
                || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException("chat confidence threshold must be in (0,1]");
        }
        this.threshold = threshold;
    }

    public ChatRoute route(ChatClassification c) {
        if (c.intent() == ChatIntent.ADVANCED_CONVERSATION
                || c.confidence().compareTo(threshold) < 0) {
            return ChatRoute.ADVANCED_AI_REQUIRED;
        }
        if (c.intent() == ChatIntent.OUT_OF_SCOPE) {
            return ChatRoute.REFUSED;
        }
        return ChatRoute.LOCAL;
    }

    public ChatRoute routeOnClassifierFailure(boolean fallbackMatched) {
        return fallbackMatched ? ChatRoute.FALLBACK : ChatRoute.ADVANCED_AI_REQUIRED;
    }

    // LLM eligibility is derived from the predicted INTENT, never from the route label (BR-24).
    public static boolean llmEligible(ChatIntent intent) {
        return Objects.requireNonNull(intent) != ChatIntent.OUT_OF_SCOPE;
    }
}
