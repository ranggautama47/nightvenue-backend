package com.nightvenue.api.shift;

import java.time.Instant;

public record ShiftSubmissionView(long id, CashierShiftStatus status, long openingFloat, long expectedCash,
                                  long actualCash, long variance, Instant submittedAt, String submitNote) {}
