package com.nightvenue.api.bottle;

import java.time.Instant;
import java.time.LocalDate;

public record BottleResponse(
        long vaultId,
        String labelCode,
        long productId,
        long venueId,
        long customerId,
        int originalVolumeMl,
        int remainingVolumeMl,
        String status,
        Instant storedAt,
        LocalDate expiryDate,
        String qrToken,
        boolean tokenAlreadyIssued
) {
}

