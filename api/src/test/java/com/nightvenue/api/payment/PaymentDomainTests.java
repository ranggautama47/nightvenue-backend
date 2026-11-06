package com.nightvenue.api.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentDomainTests {
    @Test void cashAndEdcInitialState_isPaid() {
        assertEquals(PaymentStatus.PAID, PaymentStateMachine.initialStatus(PaymentMethod.CASH));
        assertEquals(PaymentStatus.PAID, PaymentStateMachine.initialStatus(PaymentMethod.CARD_EDC));
    }

    @Test void qrisInitialState_isPending() {
        assertEquals(PaymentStatus.PENDING, PaymentStateMachine.initialStatus(PaymentMethod.QRIS_MIDTRANS));
    }

    @Test void terminalFailedCanResurrectOnlyOnProviderSettlement() {
        assertFalse(PaymentStateMachine.canTransition(PaymentStatus.FAILED, PaymentStatus.PAID, false));
        assertTrue(PaymentStateMachine.canTransition(PaymentStatus.FAILED, PaymentStatus.PAID, true));
        assertFalse(PaymentStateMachine.canTransition(PaymentStatus.EXPIRED, PaymentStatus.PAID, false));
        assertTrue(PaymentStateMachine.canTransition(PaymentStatus.EXPIRED, PaymentStatus.PAID, true));
    }

    @Test void cancelledHasNoPhase6Transition() {
        for (PaymentStatus next : PaymentStatus.values()) {
            assertFalse(PaymentStateMachine.canTransition(PaymentStatus.CANCELLED, next, true));
        }
    }

    @Test void refundedHasNoPhase6Transition() {
        for (PaymentStatus next : PaymentStatus.values()) {
            assertFalse(PaymentStateMachine.canTransition(PaymentStatus.REFUNDED, next, true));
        }
    }

    @Test void paymentAmountMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Payment(
                1, 1, 1, java.time.LocalDate.now(), null, PaymentMethod.CASH, 0, PaymentStatus.PAID,
                null, null, null, null, null, null, 0, null, null, java.time.Instant.now(), 1,
                java.time.Instant.now(), java.time.Instant.now(), 0));
    }
}
