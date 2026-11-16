package com.nightvenue.api.bottle;

public record BottleRedeemResponse(
        long vaultId,
        String labelCode,
        long orderId,
        long orderItemId,
        int redeemedVolumeMl,
        int remainingVolumeMl,
        String vaultStatus) {
}
