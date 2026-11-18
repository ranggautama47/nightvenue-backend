package com.nightvenue.api.shift;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class ShiftCutoffSchedulingConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WiringConfiguration.class);

    @Test
    void disabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ShiftCutoffScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ShiftCutoffSchedulingConfiguration.class)).isEmpty();
        });
    }

    @Test
    void disabledWhenPropertyFalse() {
        contextRunner.withPropertyValues("nightvenue.shift.cutoff.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ShiftCutoffScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ShiftCutoffSchedulingConfiguration.class)).isEmpty();
        });
    }

    @Test
    void enabledCreatesBeans() {
        contextRunner.withPropertyValues("nightvenue.shift.cutoff.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ShiftCutoffScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(ShiftCutoffSchedulingConfiguration.class)).hasSize(1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ShiftCutoffSchedulingConfiguration.class, ShiftCutoffScheduler.class})
    static class WiringConfiguration {
        @Bean
        ShiftCutoffService shiftCutoffService() {
            return Mockito.mock(ShiftCutoffService.class);
        }
    }
}
