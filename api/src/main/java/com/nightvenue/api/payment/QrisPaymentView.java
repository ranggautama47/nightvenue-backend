package com.nightvenue.api.payment;

import java.time.Instant;
import java.time.LocalDate;

/**
 * QRIS create response: the PaymentView fields (M3-A contract unchanged) plus
 * a nullable qrString. qrString is non-null only on a confirmed 201 create;
 * 202 (pending/ambiguous) and 422 (rejected) always carry null. Never exposes
 * provider_request_id, provider_reference_id, charge_state, charge_attempts
 * or any provider secret.
 */
public record QrisPaymentView(
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
        long version,
        String qrString) {

    public static QrisPaymentView from(Payment payment, String qrString) {
        return new QrisPaymentView(
                payment.id(), payment.orderId(), payment.venueId(), payment.businessDate(),
                payment.shiftId(), payment.method(), payment.amount(), payment.status(),
                payment.expiresAt(), payment.referenceNote(), payment.paidAt(),
                payment.createdBy(), payment.createdAt(), payment.updatedAt(), payment.version(),
                qrString);
    }
}
