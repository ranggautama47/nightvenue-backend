package com.nightvenue.api.businessday;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessDateServiceTests {
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private final BusinessDateService service = new BusinessDateService(
            Clock.fixed(Instant.parse("2026-10-24T22:00:00Z"), ZoneOffset.UTC));

    @Test
    void appliesJakartaCutoffBoundaries() {
        assertEquals(LocalDate.parse("2026-10-23"), atJakarta("2026-10-24T02:30:00", 6));
        assertEquals(LocalDate.parse("2026-10-23"), atJakarta("2026-10-24T05:59:59", 6));
        assertEquals(LocalDate.parse("2026-10-24"), atJakarta("2026-10-24T06:00:00", 6));
        assertEquals(LocalDate.parse("2026-10-24"), atJakarta("2026-10-24T02:30:00", 0));
    }

    @Test
    void sameInstantUsesTheSuppliedZone() {
        Instant instant = Instant.parse("2026-10-24T00:30:00Z");
        assertEquals(LocalDate.parse("2026-10-24"), service.businessDate(instant, JAKARTA, 6));
        assertEquals(LocalDate.parse("2026-10-23"), service.businessDate(instant, ZoneOffset.UTC, 6));
    }

    @Test
    void currentDateUsesInjectedClock() {
        assertEquals(LocalDate.parse("2026-10-24"), service.currentBusinessDate(JAKARTA, 6));
    }

    @Test
    void springForwardUsesWallClockHours() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZoneOffsetTransition transition = transitions(zone).stream().filter(ZoneOffsetTransition::isGap).findFirst().orElseThrow();
        LocalDate date = transition.getDateTimeBefore().toLocalDate();
        Instant sixAm = LocalDateTime.of(date, java.time.LocalTime.of(6, 0)).atZone(zone).toInstant();
        assertEquals(date, service.businessDate(sixAm, zone, 6));
        assertEquals(LocalDate.parse("2026-03-08"), service.businessDate(
                Instant.parse("2026-03-08T10:00:00Z"), zone, 6));
    }

    @Test
    void fallBackCutoffBoundaryUsesTheTransitionDate() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZoneOffsetTransition transition = transitions(zone).stream().filter(ZoneOffsetTransition::isOverlap).findFirst().orElseThrow();
        LocalDate date = transition.getDateTimeBefore().toLocalDate();
        Instant before = LocalDateTime.of(date, java.time.LocalTime.of(5, 59, 59)).atZone(zone).toInstant();
        Instant at = LocalDateTime.of(date, java.time.LocalTime.of(6, 0)).atZone(zone).toInstant();
        assertEquals(date.minusDays(1), service.businessDate(before, zone, 6));
        assertEquals(date, service.businessDate(at, zone, 6));
    }

    @Test
    void rejectsCutoffsOutsideDay() {
        assertThrows(IllegalArgumentException.class, () -> service.businessDate(Instant.EPOCH, JAKARTA, -1));
        assertThrows(IllegalArgumentException.class, () -> service.currentBusinessDate(JAKARTA, 24));
    }

    private LocalDate atJakarta(String localDateTime, int cutoff) {
        return service.businessDate(LocalDateTime.parse(localDateTime).atZone(JAKARTA).toInstant(), JAKARTA, cutoff);
    }

    private List<ZoneOffsetTransition> transitions(ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffsetTransition> found = new ArrayList<>();
        Instant cursor = Instant.parse("2026-01-01T00:00:00Z");
        while (true) {
            ZoneOffsetTransition transition = rules.nextTransition(cursor);
            if (transition == null || transition.getInstant().isAfter(Instant.parse("2027-01-01T00:00:00Z"))) break;
            found.add(transition);
            cursor = transition.getInstant().plusNanos(1);
        }
        return found;
    }
}
