package com.nightvenue.api.chat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRoutingPolicyTests {
    private static final BigDecimal THETA = new BigDecimal("0.2663504950761663");
    private final ChatRoutingPolicy policy = new ChatRoutingPolicy(THETA);

    @Test
    void localIntentsUseInclusiveExactThreshold() {
        for (ChatIntent intent : ChatIntent.values()) {
            if (!intent.isLocal()) continue;
            assertEquals(ChatRoute.ADVANCED_AI_REQUIRED, route(intent, "0.2663504950761662"));
            assertEquals(ChatRoute.LOCAL, route(intent, "0.2663504950761663"));
            assertEquals(ChatRoute.LOCAL, route(intent, "0.2663504950761664"));
        }
        assertEquals(ChatRoute.LOCAL, route(ChatIntent.OPENING_HOURS, "0.26635049507616630"));
    }

    @Test
    void advancedAndOutOfScopeRoutesFollowPolicy() {
        for (String value : List.of("0", "0.5", "1")) {
            assertEquals(ChatRoute.ADVANCED_AI_REQUIRED, route(ChatIntent.ADVANCED_CONVERSATION, value));
        }
        assertEquals(ChatRoute.REFUSED, route(ChatIntent.OUT_OF_SCOPE, "0.2663504950761663"));
        assertEquals(ChatRoute.REFUSED, route(ChatIntent.OUT_OF_SCOPE, "1"));
        assertEquals(ChatRoute.ADVANCED_AI_REQUIRED, route(ChatIntent.OUT_OF_SCOPE, "0.2663504950761662"));
        assertEquals(ChatRoute.ADVANCED_AI_REQUIRED, route(ChatIntent.OUT_OF_SCOPE, "0"));
    }

    @Test
    void injectedThresholdFallbackAndLlmEligibility() {
        ChatRoutingPolicy half = new ChatRoutingPolicy(new BigDecimal("0.5"));
        assertEquals(ChatRoute.ADVANCED_AI_REQUIRED, half.route(classification(ChatIntent.OPENING_HOURS, "0.4")));
        assertEquals(ChatRoute.LOCAL, half.route(classification(ChatIntent.OPENING_HOURS, "0.5")));
        for (ChatIntent intent : ChatIntent.values()) {
            for (String confidence : List.of("0", "0.1", "0.2663504950761663", "0.9", "1")) {
                assertFalse(policy.route(classification(intent, confidence)) == ChatRoute.LLM);
            }
            assertEquals(intent != ChatIntent.OUT_OF_SCOPE, ChatRoutingPolicy.llmEligible(intent));
        }
        assertThrows(NullPointerException.class, () -> ChatRoutingPolicy.llmEligible(null));
        assertEquals(ChatRoute.FALLBACK, policy.routeOnClassifierFailure(true));
        assertEquals(ChatRoute.ADVANCED_AI_REQUIRED, policy.routeOnClassifierFailure(false));
    }

    @Test
    void validatesThresholdAndReadsProperties() {
        for (BigDecimal value : List.of(BigDecimal.ZERO, new BigDecimal("-0.1"), new BigDecimal("1.0000001"))) {
            assertThrows(IllegalStateException.class, () -> new ChatRoutingPolicy(value));
        }
        assertThrows(IllegalStateException.class, () -> new ChatRoutingPolicy((BigDecimal) null));
        ChatRoutingPolicy one = new ChatRoutingPolicy(BigDecimal.ONE);
        assertEquals(ChatRoute.LOCAL, one.route(classification(ChatIntent.OPENING_HOURS, "1")));
        ChatProperties properties = new ChatProperties(2000, THETA, 10, 32);
        assertEquals(ChatRoute.LOCAL, new ChatRoutingPolicy(properties)
                .route(classification(ChatIntent.OPENING_HOURS, THETA.toPlainString())));
    }

    private ChatRoute route(ChatIntent intent, String confidence) {
        return policy.route(classification(intent, confidence));
    }

    private static ChatClassification classification(ChatIntent intent, String confidence) {
        return new ChatClassification(intent, new BigDecimal(confidence), "model");
    }
}
