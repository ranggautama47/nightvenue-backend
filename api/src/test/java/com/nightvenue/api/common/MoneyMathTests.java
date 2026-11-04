package com.nightvenue.api.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyMathTests {

    @Test
    void zeroBpsOrZeroBaseReturnsZero() {
        assertEquals(0L, MoneyMath.applyBps(0L, 500));
        assertEquals(0L, MoneyMath.applyBps(100_000L, 0));
        assertEquals(0L, MoneyMath.applyBps(0L, 0));
    }

    @Test
    void standardCalculation() {
        // 5% (500 bps) of 100,000 = 5,000
        assertEquals(5_000L, MoneyMath.applyBps(100_000L, 500));
        // 11% (1100 bps) of 100,000 = 11,000
        assertEquals(11_000L, MoneyMath.applyBps(100_000L, 1100));
        // 100% (10,000 bps) of 75,000 = 75,000
        assertEquals(75_000L, MoneyMath.applyBps(75_000L, 10_000));
    }

    @Test
    void halfUpRoundingBoundary() {
        // base = 10, bps = 5 -> 10 * 5 / 10000 = 50 / 10000 = 0.005 -> 0
        // To test exactly 0.5: base = 1, bps = 5000 -> 1 * 5000 / 10000 = 0.5 -> rounds up to 1
        assertEquals(1L, MoneyMath.applyBps(1L, 5000));

        // base = 1, bps = 4999 -> 0.4999 -> rounds down to 0
        assertEquals(0L, MoneyMath.applyBps(1L, 4999));

        // base = 3, bps = 500 -> 3 * 500 / 10000 = 1500 / 10000 = 0.15 -> 0
        assertEquals(0L, MoneyMath.applyBps(3L, 500));

        // base = 7, bps = 500 -> 7 * 500 / 10000 = 3500 / 10000 = 0.35 -> 0
        assertEquals(0L, MoneyMath.applyBps(7L, 500));

        // base = 10, bps = 500 -> 10 * 500 / 10000 = 5000 / 10000 = 0.5 -> 1
        assertEquals(1L, MoneyMath.applyBps(10L, 500));

        // base = 11, bps = 500 -> 11 * 500 / 10000 = 5500 / 10000 = 0.55 -> 1
        assertEquals(1L, MoneyMath.applyBps(11L, 500));

        // base = 30, bps = 500 -> 15000 / 10000 = 1.5 -> 2
        assertEquals(2L, MoneyMath.applyBps(30L, 500));
    }

    @Test
    void largeValuesWithoutPrematureOverflow() {
        // 1 trillion (1,000,000,000,000) * 11% (1100 bps) = 110,000,000,000
        assertEquals(110_000_000_000L, MoneyMath.applyBps(1_000_000_000_000L, 1100));

        // Max reasonable value
        long largeBase = 50_000_000_000_000L; // 50 trillion
        assertEquals(2_500_000_000_000L, MoneyMath.applyBps(largeBase, 500));
    }

    @Test
    void negativeBpsThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> MoneyMath.applyBps(100_000L, -1));
    }

    @Test
    void arithmeticOverflowThrowsArithmeticException() {
        // Long.MAX_VALUE * 10000 bps = Long.MAX_VALUE (exact)
        assertEquals(Long.MAX_VALUE, MoneyMath.applyBps(Long.MAX_VALUE, 10_000));

        // Long.MAX_VALUE * 20000 bps -> 2 * Long.MAX_VALUE which exceeds Long.MAX_VALUE
        assertThrows(ArithmeticException.class, () -> MoneyMath.applyBps(Long.MAX_VALUE, 20_000));
    }
}

