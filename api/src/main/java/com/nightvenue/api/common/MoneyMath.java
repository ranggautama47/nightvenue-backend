package com.nightvenue.api.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Central financial and basis-point calculation helper (ADR-007, Phase 5B D5).
 * All calculations use exact integer/BigDecimal math; double/float are forbidden.
 */
public final class MoneyMath {

    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private MoneyMath() {
    }

    /**
     * Calculates the amount from a base and basis points (bps).
     * Formula: round_half_up(base * bps / 10000).
     *
     * @param base the base amount (e.g. subtotal - discount)
     * @param bps basis points (1 bps = 0.01%, 100 bps = 1%, 10000 bps = 100%)
     * @return the calculated amount rounded HALF_UP
     * @throws ArithmeticException if the result overflows long
     * @throws IllegalArgumentException if bps is negative
     */
    public static long applyBps(long base, int bps) {
        if (bps < 0) {
            throw new IllegalArgumentException("Basis points cannot be negative");
        }
        if (base == 0L || bps == 0) {
            return 0L;
        }
        BigDecimal baseBd = BigDecimal.valueOf(base);
        BigDecimal bpsBd = BigDecimal.valueOf(bps);
        return baseBd.multiply(bpsBd)
                .divide(TEN_THOUSAND, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}

