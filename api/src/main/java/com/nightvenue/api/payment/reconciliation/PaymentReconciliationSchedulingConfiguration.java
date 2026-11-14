package com.nightvenue.api.payment.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring scheduling is enabled only when reconciliation is explicitly turned on.
 * With the default {@code enabled=false} this configuration is absent, so no
 * {@code @Scheduled} bean is registered in normal runtime or in tests.
 */
@Configuration
@ConditionalOnProperty(prefix = "nightvenue.payment.reconciliation", name = "enabled", havingValue = "true")
@EnableScheduling
public class PaymentReconciliationSchedulingConfiguration {
}
