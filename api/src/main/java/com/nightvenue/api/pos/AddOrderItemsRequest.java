package com.nightvenue.api.pos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AddOrderItemsRequest(
        @NotNull @Size(min = 1, max = 50) List<@Valid ItemRow> items
) {
    public record ItemRow(
            @NotNull @Positive Long productId,
            @NotNull @Min(1) @Max(999) Integer quantity
    ) {}
}

