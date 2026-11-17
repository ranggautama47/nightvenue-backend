package com.nightvenue.api.shift;

import java.time.Instant;
import java.time.LocalDate;

public record ShiftReviewView(long id, long venueId, long cashierId, LocalDate businessDate,
                              CashierShiftStatus status, long openingFloat, Instant submittedAt,
                              long expectedCash, long actualCash, long variance, String submitNote,
                              String reviewNote, long reviewedBy, Instant reviewedAt,
                              Instant forcedReviewAt) {}
