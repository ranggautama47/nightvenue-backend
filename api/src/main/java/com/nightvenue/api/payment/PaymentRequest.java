package com.nightvenue.api.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
        @NotNull PaymentMethod method,
        @NotNull @Positive Long amount,
        @Size(max = 100) String referenceNote) {
}
