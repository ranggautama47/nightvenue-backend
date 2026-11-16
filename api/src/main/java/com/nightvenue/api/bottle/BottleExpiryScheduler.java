package com.nightvenue.api.bottle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(prefix = "nightvenue.bottle.expiry", name = "enabled", havingValue = "true", matchIfMissing = false)
public class BottleExpiryScheduler {
    private final BottleExpiryService expiry;
    private final Clock clock;

    public BottleExpiryScheduler(BottleExpiryService expiry, Clock clock) {
        this.expiry = expiry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nightvenue.bottle.expiry.fixed-delay:PT1H}")
    public void expireDue() {
        expiry.expireDue(clock.instant());
    }
}
