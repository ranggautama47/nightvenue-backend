package com.nightvenue.api.bottle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RedeemBottleRequest(
        @NotBlank String qrToken,
        @NotNull @Positive Long orderId,
        @NotNull @Positive Integer volumeMl,
        @NotNull Boolean ownerConfirmed) {
}
