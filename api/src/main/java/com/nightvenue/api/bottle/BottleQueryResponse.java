package com.nightvenue.api.bottle;

import java.time.Instant;
import java.time.LocalDate;

public record BottleQueryResponse(
        long vaultId,
        String labelCode,
        long venueId,
        long customerId,
        long productId,
        String productName,
        int originalVolumeMl,
        int remainingVolumeMl,
        Instant storedAt,
        LocalDate expiryDate,
        String status) {
}
