package com.nightvenue.api.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPropertiesBindingTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsAreBound() {
        contextRunner.run(context -> {
            assertTrue(context.isRunning());
            ChatProperties properties = context.getBean(ChatProperties.class);
            assertEquals(2000, properties.getClassifyTimeoutMs());
            assertEquals(10, properties.getRateLimitPerMin());
            assertEquals(32, properties.getClassifyMaxConcurrency());
            assertTrue(properties.toString().contains("confidenceThreshold=null"));
            assertTrue(!properties.toString().contains("token"));
        });
    }

    @Test
    void chatValuesBindWithExactDecimal() {
        String theta = "0.2663504950761663";
        contextRunner.withPropertyValues(
                "nightvenue.chat.classify-timeout-ms=300",
                "nightvenue.chat.confidence-threshold=" + theta,
                "nightvenue.chat.rate-limit-per-min=12",
                "nightvenue.chat.classify-max-concurrency=4")
                .run(context -> {
                    ChatProperties properties = context.getBean(ChatProperties.class);
                    assertEquals(300, properties.getClassifyTimeoutMs());
                    assertEquals(0, properties.getConfidenceThreshold().compareTo(new BigDecimal(theta)));
                    assertEquals(theta, properties.getConfidenceThreshold().toPlainString());
                    assertEquals(12, properties.getRateLimitPerMin());
                    assertEquals(4, properties.getClassifyMaxConcurrency());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatProperties.class)
    static class PropertiesConfiguration {
    }
}
