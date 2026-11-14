package com.nightvenue.api.bottle;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class BottlePropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void missingRetentionDaysFailsValidation() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("nightvenue.bottle");
        });
    }

    @Test
    void zeroRetentionDaysFailsValidation() {
        assertInvalid("0");
    }

    @Test
    void negativeRetentionDaysFailsValidation() {
        assertInvalid("-1");
    }

    @Test
    void positiveRetentionDaysStartsContext() {
        contextRunner.withPropertyValues("nightvenue.bottle.retention-days=30").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BottleProperties.class).getRetentionDays()).isEqualTo(30);
        });
    }

    private void assertInvalid(String value) {
        contextRunner.withPropertyValues("nightvenue.bottle.retention-days=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("nightvenue.bottle");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BottleProperties.class)
    static class PropertiesConfiguration {
    }
}
