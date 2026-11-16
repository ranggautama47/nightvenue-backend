package com.nightvenue.api.bottle;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DepositBottleRequest(
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long orderItemId
) {
}

