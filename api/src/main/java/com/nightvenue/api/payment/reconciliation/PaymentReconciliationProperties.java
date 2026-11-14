package com.nightvenue.api.payment.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Reconciliation settings. Every default here is a conservative placeholder and
 * is PM to confirm.
 */
@ConfigurationProperties(prefix = "nightvenue.payment.reconciliation")
public class PaymentReconciliationProperties {

    /** PM to confirm: the job is off unless explicitly enabled. */
    private boolean enabled = false;

    /** PM to confirm: delay between cycles when the scheduler is enabled. */
    private Duration fixedDelay = Duration.ofMinutes(5);

    /** PM to confirm: a PENDING row must be at least this old (charge_started_at) to be checked. */
    private Duration minAge = Duration.ofHours(1);

    /** PM to confirm: maximum number of candidate ids loaded per cycle. */
    private int batchSize = 50;

    /** PM to confirm: maximum number of candidates actually queried per cycle. */
    private int maxPerCycle = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getFixedDelay() {
        return fixedDelay;
    }

    public void setFixedDelay(Duration fixedDelay) {
        this.fixedDelay = fixedDelay;
    }

    public Duration getMinAge() {
        return minAge;
    }

    public void setMinAge(Duration minAge) {
        this.minAge = minAge;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxPerCycle() {
        return maxPerCycle;
    }

    public void setMaxPerCycle(int maxPerCycle) {
        this.maxPerCycle = maxPerCycle;
    }
}
