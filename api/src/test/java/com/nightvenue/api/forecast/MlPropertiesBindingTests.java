package com.nightvenue.api.forecast;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlPropertiesBindingTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void absentPropertiesUseDefaultsAndAllowBlankConfiguration() {
        contextRunner.run(context -> {
            assertTrue(context.isRunning());
            MlProperties properties = context.getBean(MlProperties.class);
            assertEquals("", properties.getServiceUrl());
            assertEquals("", properties.getInternalToken());
            assertEquals(10_000, properties.getForecastTimeoutMs());
        });
    }

    @Test
    void configuredValuesBindFromNightvenueMlPrefix() {
        contextRunner
                .withPropertyValues(
                        "nightvenue.ml.service-url=http://127.0.0.1:8000",
                        "nightvenue.ml.internal-token=local-token",
                        "nightvenue.ml.forecast-timeout-ms=2500")
                .run(context -> {
                    MlProperties properties = context.getBean(MlProperties.class);
                    assertEquals("http://127.0.0.1:8000", properties.getServiceUrl());
                    assertEquals("local-token", properties.getInternalToken());
                    assertEquals(2_500, properties.getForecastTimeoutMs());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MlProperties.class)
    static class PropertiesConfiguration {
    }
}
