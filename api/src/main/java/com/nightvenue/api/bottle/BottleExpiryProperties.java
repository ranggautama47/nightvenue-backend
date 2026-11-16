package com.nightvenue.api.bottle;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@Component
@ConfigurationProperties("nightvenue.bottle.expiry")
public class BottleExpiryProperties {
    private boolean enabled = false;

    @NotNull
    private Duration fixedDelay = Duration.ofHours(1);

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

    @AssertTrue(message = "nightvenue.bottle.expiry.fixed-delay must be positive")
    public boolean isFixedDelayPositive() {
        return fixedDelay != null && !fixedDelay.isZero() && !fixedDelay.isNegative();
    }
}
