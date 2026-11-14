package com.nightvenue.api.payment.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the reconciliation properties. Scheduling itself lives in
 * {@link PaymentReconciliationSchedulingConfiguration} and is conditional, so
 * the default application context (and every test context) has no scheduled
 * task at all; tests call {@link PaymentReconciliationService#reconcile()}
 * directly.
 */
@Configuration
@EnableConfigurationProperties(PaymentReconciliationProperties.class)
public class PaymentReconciliationConfiguration {
}
