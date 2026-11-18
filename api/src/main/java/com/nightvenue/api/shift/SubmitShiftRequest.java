package com.nightvenue.api.shift;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SubmitShiftRequest(
        @NotNull @PositiveOrZero Long actualCash,
        @Size(max = 255) String note) {}
