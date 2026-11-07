package com.nightvenue.api.shift;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record OpenShiftRequest(
        @NotNull @Positive Long venueId,
        @NotNull @PositiveOrZero Long openingFloat) {
}
