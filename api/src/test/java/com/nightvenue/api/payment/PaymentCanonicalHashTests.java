package com.nightvenue.api.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the OD-10 canonical hashing format
 * v1|{orderId}|{METHOD}|{amount}|{normalizedReferenceNote}
 */
class PaymentCanonicalHashTests {

    @Test
    void canonicalStringMatchesOd10Format() {
        assertEquals("v1|42|CASH|10000|", PaymentService.canonicalString(42, "CASH", 10000, null));
        assertEquals("v1|42|CASH|10000|-", PaymentService.canonicalString(42, "CASH", 10000, "-"));
        assertEquals("v1|42|CASH|10000|EDC123", PaymentService.canonicalString(42, "CASH", 10000, "EDC123"));
    }

    @Test
    void normalizeReferenceNoteRules() {
        assertNull(PaymentService.normalizeReferenceNote(null));
        assertNull(PaymentService.normalizeReferenceNote(""));
        assertNull(PaymentService.normalizeReferenceNote("   "));
        assertEquals("EDC123", PaymentService.normalizeReferenceNote("  EDC123 \t"));
        // literal "-" is a value and stays distinct from null
        assertEquals("-", PaymentService.normalizeReferenceNote("-"));
        assertEquals("-", PaymentService.normalizeReferenceNote("  -  "));
        // PM-locked rule: trim only — interior spaces are never removed.
        // Input is exactly 100 raw chars (" x" x50); trimming strips the leading
        // space (there is no trailing space) but keeps every interior space.
        assertEquals("x" + " x".repeat(49), PaymentService.normalizeReferenceNote(" x".repeat(50)));
    }
}
