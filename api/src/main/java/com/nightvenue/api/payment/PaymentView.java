package com.nightvenue.api.payment;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Safe public projection of a payment for GET /api/v1/payments/{paymentId}.
 * Deliberately excludes provider identifiers (provider_order_id,
 * provider_transaction_id, provider_idempotency_key) and charge orchestration
 * metadata (provider, charge_state, charge_started_at, charge_attempts):
 * the idempotency key is credential-adjacent and the rest is provider-internal.
 */
public record PaymentView(
        long id,
        long orderId,
        long venueId,
        LocalDate businessDate,
        Long shiftId,
        PaymentMethod method,
        long amount,
        PaymentStatus status,
        Instant expiresAt,
        String referenceNote,
        Instant paidAt,
        long createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static PaymentView from(Payment payment) {
        return new PaymentView(
                payment.id(), payment.orderId(), payment.venueId(), payment.businessDate(),
                payment.shiftId(), payment.method(), payment.amount(), payment.status(),
                payment.expiresAt(), payment.referenceNote(), payment.paidAt(),
                payment.createdBy(), payment.createdAt(), payment.updatedAt(), payment.version());
    }
}
