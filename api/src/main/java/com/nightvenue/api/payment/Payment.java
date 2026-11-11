package com.nightvenue.api.payment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Payment(
        long id,
        long orderId,
        long venueId,
        LocalDate businessDate,
        Long shiftId,
        PaymentMethod method,
        long amount,
        PaymentStatus status,
        String provider,
        String providerOrderId,
        String providerTransactionId,
        UUID providerIdempotencyKey,
        String providerReferenceId,
        String providerRequestId,
        String qrPayload,
        ChargeState chargeState,
        Instant chargeStartedAt,
        int chargeAttempts,
        Instant expiresAt,
        String referenceNote,
        Instant paidAt,
        long createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Payment {
        if (amount <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
    }

    /** Legacy-shape constructor (pre-V9 callers); Xendit identity columns default to NULL. */
    public Payment(long id, long orderId, long venueId, LocalDate businessDate, Long shiftId,
                   PaymentMethod method, long amount, PaymentStatus status, String provider,
                   String providerOrderId, String providerTransactionId, UUID providerIdempotencyKey,
                   ChargeState chargeState, Instant chargeStartedAt, int chargeAttempts,
                   Instant expiresAt, String referenceNote, Instant paidAt, long createdBy,
                   Instant createdAt, Instant updatedAt, long version) {
        this(id, orderId, venueId, businessDate, shiftId, method, amount, status, provider,
                providerOrderId, providerTransactionId, providerIdempotencyKey,
                null, null, null,
                chargeState, chargeStartedAt, chargeAttempts, expiresAt, referenceNote, paidAt,
                createdBy, createdAt, updatedAt, version);
    }
}
