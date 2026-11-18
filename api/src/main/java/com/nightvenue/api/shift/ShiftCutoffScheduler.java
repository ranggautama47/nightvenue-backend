package com.nightvenue.api.shift;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "nightvenue.shift.cutoff", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ShiftCutoffScheduler {
    private final ShiftCutoffService cutoffService;

    public ShiftCutoffScheduler(ShiftCutoffService cutoffService) {
        this.cutoffService = cutoffService;
    }

    @Scheduled(fixedDelayString = "${nightvenue.shift.cutoff.fixed-delay:PT1M}")
    public void runCutoff() {
        cutoffService.runCutoff();
    }
}
