package com.nightvenue.api.businessday;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class BusinessDateService {
    private final Clock clock;

    public BusinessDateService(Clock clock) {
        this.clock = clock;
    }

    public LocalDate businessDate(Instant instant, ZoneId zone, int cutoffHour) {
        if (cutoffHour < 0 || cutoffHour > 23) {
            throw new IllegalArgumentException("cutoffHour must be between 0 and 23");
        }
        return instant.atZone(zone).toLocalDateTime().minusHours(cutoffHour).toLocalDate();
    }

    public LocalDate currentBusinessDate(ZoneId zone, int cutoffHour) {
        return businessDate(clock.instant(), zone, cutoffHour);
    }
}
