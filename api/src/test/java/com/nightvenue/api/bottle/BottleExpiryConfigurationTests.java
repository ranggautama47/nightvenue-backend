package com.nightvenue.api.bottle;

import com.nightvenue.api.payment.reconciliation.PaymentReconciliationSchedulingConfiguration;
import com.nightvenue.api.payment.reconciliation.PaymentReconciliationScheduler;
import com.nightvenue.api.payment.reconciliation.PaymentReconciliationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BottleExpiryConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WiringConfiguration.class);
    private final ApplicationContextRunner propertiesContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void disabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(BottleExpiryScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(BottleExpirySchedulingConfiguration.class)).isEmpty();
        });
    }

    @Test
    void enabledCreatesBeans() {
        contextRunner.withPropertyValues("nightvenue.bottle.expiry.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(BottleExpiryScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(BottleExpirySchedulingConfiguration.class)).hasSize(1);
            assertThat(context.getBean(BottleExpiryProperties.class).getFixedDelay()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void independenceBothWays() {
        contextRunner.withPropertyValues(
                "nightvenue.bottle.expiry.enabled=true",
                "nightvenue.payment.reconciliation.enabled=false").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(BottleExpiryScheduler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PaymentReconciliationScheduler.class)).isEmpty();
                    assertThat(context.getBeansOfType(PaymentReconciliationSchedulingConfiguration.class)).isEmpty();
                });

        contextRunner.withPropertyValues("nightvenue.payment.reconciliation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(PaymentReconciliationScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(BottleExpiryScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(BottleExpirySchedulingConfiguration.class)).isEmpty();
        });
    }

    @Test
    void invalidFixedDelayFailsBootstrap() {
        propertiesContextRunner.withPropertyValues(
                "nightvenue.bottle.expiry.enabled=true",
                "nightvenue.bottle.expiry.fixed-delay=PT0S").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("nightvenue.bottle.expiry");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BottleExpiryProperties.class)
    @Import({BottleExpirySchedulingConfiguration.class, BottleExpiryScheduler.class,
            PaymentReconciliationSchedulingConfiguration.class, PaymentReconciliationScheduler.class})
    static class WiringConfiguration {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        BottleExpiryService bottleExpiryService() {
            return Mockito.mock(BottleExpiryService.class);
        }

        @Bean
        PaymentReconciliationService paymentReconciliationService() {
            return Mockito.mock(PaymentReconciliationService.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BottleExpiryProperties.class)
    static class PropertiesConfiguration {
    }
}
