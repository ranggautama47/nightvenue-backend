package com.nightvenue.api.shift;

import java.time.Instant;
import java.time.LocalDate;

public record CashierShift(
        long id,
        long venueId,
        long cashierId,
        LocalDate businessDate,
        CashierShiftStatus status,
        long openingFloat,
        Instant openedAt,
        Instant forcedReviewAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
