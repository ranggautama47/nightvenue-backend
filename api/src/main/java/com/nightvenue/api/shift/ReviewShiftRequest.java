package com.nightvenue.api.shift;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ReviewShiftRequest(
        @PositiveOrZero Long actualCash,
        @Size(max = 255) String note) {}
