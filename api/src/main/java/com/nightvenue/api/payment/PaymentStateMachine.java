package com.nightvenue.api.payment;

public final class PaymentStateMachine {
    private PaymentStateMachine() {}

    public static PaymentStatus initialStatus(PaymentMethod method) {
        return method == PaymentMethod.QRIS_MIDTRANS ? PaymentStatus.PENDING : PaymentStatus.PAID;
    }

    public static boolean canTransition(
            PaymentStatus current,
            PaymentStatus next,
            boolean providerSettlementConfirmed) {
        if (current == next || current == PaymentStatus.CANCELLED || current == PaymentStatus.REFUNDED) {
            return false;
        }
        if (current == PaymentStatus.PENDING) {
            return next == PaymentStatus.PAID || next == PaymentStatus.FAILED || next == PaymentStatus.EXPIRED;
        }
        return (current == PaymentStatus.FAILED || current == PaymentStatus.EXPIRED)
                && next == PaymentStatus.PAID && providerSettlementConfirmed;
    }
}
