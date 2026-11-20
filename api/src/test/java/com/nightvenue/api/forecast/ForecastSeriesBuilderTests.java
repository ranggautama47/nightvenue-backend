package com.nightvenue.api.forecast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForecastSeriesBuilderTests {
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 31);
    private final ForecastSeriesBuilder builder = new ForecastSeriesBuilder();

    @Test
    void emptyLedgerReportsZeroAvailableDays() {
        InsufficientForecastDataException exception = assertThrows(
                InsufficientForecastDataException.class, () -> builder.build(AS_OF, 7, List.of()));

        assertEquals(0, exception.availableDays());
        assertEquals(28, exception.requiredDays());
        assertEquals("Insufficient forecast data: available=0 required=28", exception.getMessage());
    }

    @Test
    void rowsOnlyAfterAsOfReportZeroAvailableDays() {
        InsufficientForecastDataException exception = assertThrows(
                InsufficientForecastDataException.class,
                () -> builder.build(AS_OF, 7, List.of(day(AS_OF.plusDays(1), "4.0"))));

        assertEquals(0, exception.availableDays());
        assertEquals(28, exception.requiredDays());
    }

    @Test
    void consecutiveTwentyEightDaysPassThroughValues() {
        LocalDate first = AS_OF.minusDays(27);
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(first, 28, "1.250");

        ForecastSeriesBuilder.SeriesResult result = builder.build(AS_OF, 7, ledger);

        assertEquals(28, result.series().size());
        assertEquals(new BigDecimal("1.250"), result.series().get(0).quantity());
        assertEquals(new BigDecimal("1.250"), result.series().get(27).quantity());
    }

    @Test
    void missingMiddleDateIsZeroFilledWithoutChangingNeighbours() {
        LocalDate first = AS_OF.minusDays(27);
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(first, 28, "2.000");
        ledger.remove(10);

        List<MlForecastRequest.Observation> series = builder.build(AS_OF, 7, ledger).series();

        assertEquals(new BigDecimal("2.000"), series.get(9).quantity());
        assertEquals(first.plusDays(10), series.get(10).businessDate());
        assertEquals(BigDecimal.ZERO, series.get(10).quantity());
        assertEquals(new BigDecimal("2.000"), series.get(11).quantity());
    }

    @Test
    void firstLedgerDateStartsTheSeriesAndLastDateIsAsOf() {
        LocalDate first = AS_OF.minusDays(27);

        List<MlForecastRequest.Observation> series = builder.build(
                AS_OF, 7, ledgerDays(first, 28, "1")).series();

        assertEquals(first, series.get(0).businessDate());
        assertEquals(AS_OF, series.get(series.size() - 1).businessDate());
    }

    @Test
    void spanTwentySevenFailsAndTwentyEightPassesForSevenDayHorizon() {
        InsufficientForecastDataException shortSpan = assertThrows(
                InsufficientForecastDataException.class,
                () -> builder.build(AS_OF, 7, ledgerDays(AS_OF.minusDays(26), 27, "1")));
        assertEquals(27, shortSpan.availableDays());
        assertEquals(28, shortSpan.requiredDays());

        assertEquals(28, builder.build(AS_OF, 7,
                ledgerDays(AS_OF.minusDays(27), 28, "1")).inputDays());
    }

    @Test
    void horizonSpecificMinimumSpansAreEnforced() {
        assertEquals(28, builder.build(AS_OF, 1,
                ledgerDays(AS_OF.minusDays(27), 28, "1")).inputDays());
        assertEquals(28, builder.build(AS_OF, 7,
                ledgerDays(AS_OF.minusDays(27), 28, "1")).inputDays());

        InsufficientForecastDataException shortFourteenDaySpan = assertThrows(
                InsufficientForecastDataException.class,
                () -> builder.build(AS_OF, 14, ledgerDays(AS_OF.minusDays(33), 34, "1")));
        assertEquals(34, shortFourteenDaySpan.availableDays());
        assertEquals(35, shortFourteenDaySpan.requiredDays());
        assertEquals(35, builder.build(AS_OF, 14,
                ledgerDays(AS_OF.minusDays(34), 35, "1")).inputDays());
    }

    @Test
    void horizonOutsideSupportedRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(AS_OF, 0, ledgerDays(AS_OF.minusDays(27), 28, "1")));
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(AS_OF, 15, ledgerDays(AS_OF.minusDays(27), 28, "1")));
    }

    @Test
    void negativeQuantitiesAreClampedAndZeroAndPositiveValuesRemain() {
        LocalDate first = AS_OF.minusDays(27);
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(first, 28, "1.000");
        ledger.set(0, day(first, "-2.500"));
        ledger.set(1, day(first.plusDays(1), "0.000"));
        ledger.set(2, day(first.plusDays(2), "3.125"));

        List<MlForecastRequest.Observation> series = builder.build(AS_OF, 7, ledger).series();

        assertEquals(BigDecimal.ZERO, series.get(0).quantity());
        assertEquals(new BigDecimal("0.000"), series.get(1).quantity());
        assertEquals(new BigDecimal("3.125"), series.get(2).quantity());
    }

    @Test
    void rowAfterAsOfIsIgnored() {
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(AS_OF.minusDays(27), 28, "1");
        ledger.add(day(AS_OF.plusDays(1), "500"));

        List<MlForecastRequest.Observation> series = builder.build(AS_OF, 7, ledger).series();

        assertEquals(28, series.size());
        assertEquals(AS_OF, series.get(series.size() - 1).businessDate());
    }

    @Test
    void historyIsLimitedToNinetyDays() {
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(AS_OF.minusDays(99), 100, "1");

        List<MlForecastRequest.Observation> series = builder.build(AS_OF, 7, ledger).series();

        assertEquals(90, series.size());
        assertEquals(AS_OF.minusDays(89), series.get(0).businessDate());
        assertEquals(AS_OF, series.get(89).businessDate());
    }

    @Test
    void duplicateDatesAreSummed() {
        LocalDate first = AS_OF.minusDays(27);
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(first, 28, "1.000");
        ledger.add(day(first.plusDays(5), "2.500"));

        List<MlForecastRequest.Observation> series = builder.build(AS_OF, 7, ledger).series();

        assertEquals(new BigDecimal("3.500"), series.get(5).quantity());
        assertEquals(28, series.size());
    }

    @Test
    void decimalPrecisionAndScaleArePreserved() {
        LocalDate first = AS_OF.minusDays(27);
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(first, 28, "1");
        ledger.set(12, day(first.plusDays(12), "123.456"));

        BigDecimal quantity = builder.build(AS_OF, 7, ledger).series().get(12).quantity();

        assertEquals(new BigDecimal("123.456"), quantity);
        assertEquals(3, quantity.scale());
    }

    @Test
    void outputIsSortedConsecutiveAndReportsItsSize() {
        LocalDate first = AS_OF.minusDays(27);
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(first, 28, "1");
        Collections.reverse(ledger);

        ForecastSeriesBuilder.SeriesResult result = builder.build(AS_OF, 7, ledger);

        assertEquals(result.series().size(), result.inputDays());
        for (int index = 1; index < result.series().size(); index++) {
            assertEquals(result.series().get(index - 1).businessDate().plusDays(1),
                    result.series().get(index).businessDate());
        }
        assertThrows(UnsupportedOperationException.class,
                () -> result.series().add(new MlForecastRequest.Observation(AS_OF.plusDays(1), BigDecimal.ONE)));
    }

    @Test
    void nullArgumentsAndElementsAreRejected() {
        List<ForecastRepository.LedgerDay> ledger = ledgerDays(AS_OF.minusDays(27), 28, "1");
        assertThrows(NullPointerException.class, () -> builder.build(null, 7, ledger));
        assertThrows(NullPointerException.class, () -> builder.build(AS_OF, 7, null));
        assertThrows(NullPointerException.class,
                () -> builder.build(AS_OF, 7, Collections.singletonList(null)));
    }

    private static List<ForecastRepository.LedgerDay> ledgerDays(
            LocalDate firstDate, int count, String quantity) {
        List<ForecastRepository.LedgerDay> ledger = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ledger.add(day(firstDate.plusDays(index), quantity));
        }
        return ledger;
    }

    private static ForecastRepository.LedgerDay day(LocalDate date, String quantity) {
        return new ForecastRepository.LedgerDay(date, new BigDecimal(quantity));
    }
}
