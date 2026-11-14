package com.nightvenue.api.payment.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin scheduled trigger; all logic lives in {@link PaymentReconciliationService}.
 * Only present when reconciliation is enabled. Multi-instance deployments rely on
 * the per-payment CAS inside the settlement transaction, not on a distributed lock.
 */
@Component
@ConditionalOnProperty(prefix = "nightvenue.payment.reconciliation", name = "enabled", havingValue = "true")
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService reconciliation;

    public PaymentReconciliationScheduler(PaymentReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${nightvenue.payment.reconciliation.fixed-delay:PT5M}")
    public void runCycle() {
        reconciliation.reconcile();
    }
}
