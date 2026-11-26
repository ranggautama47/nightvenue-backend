package com.nightvenue.api.chat;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OpeningHoursFormatterTests {

    // The verified active-demo seed JSON
    private static final String SEED_JSON =
        "{\"mon\":[\"18:00\",\"02:00\"],\"tue\":[\"18:00\",\"02:00\"]," +
        "\"wed\":[\"18:00\",\"02:00\"],\"thu\":[\"18:00\",\"02:00\"]," +
        "\"fri\":[\"18:00\",\"03:00\"],\"sat\":[\"18:00\",\"03:00\"]," +
        "\"sun\":[\"18:00\",\"02:00\"]}";

    @Test
    void seedJsonProducesExpectedContent() {
        Optional<String> result = OpeningHoursFormatter.format(SEED_JSON);
        assertTrue(result.isPresent());
        String out = result.get();
        assertTrue(out.contains("Senin 18:00-02:00"), "Must contain Senin");
        assertTrue(out.contains("Jumat 18:00-03:00"), "Must contain Jumat");
        assertTrue(out.contains("hingga keesokan harinya"), "Must contain next-day suffix");
    }

    @Test
    void sameDayRangeHasNoNextDaySuffix() {
        String json = "{\"mon\":[\"10:00\",\"22:00\"]}";
        Optional<String> result = OpeningHoursFormatter.format(json);
        assertTrue(result.isPresent());
        assertFalse(result.get().contains("keesokan"), "Same-day range must not have next-day suffix");
        assertTrue(result.get().contains("Senin 10:00-22:00"));
    }

    @Test
    void nullReturnsEmpty() {
        assertEquals(Optional.empty(), OpeningHoursFormatter.format(null));
    }

    @Test
    void emptyStringReturnsEmpty() {
        assertEquals(Optional.empty(), OpeningHoursFormatter.format(""));
    }

    @Test
    void whitespaceOnlyReturnsEmpty() {
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("   "));
    }

    @Test
    void emptyObjectReturnsEmpty() {
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("{}"));
    }

    @Test
    void arrayRootReturnsEmpty() {
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("[]"));
    }

    @Test
    void malformedJsonReturnsEmpty() {
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("{not valid json"));
    }

    @Test
    void nonObjectRootReturnsEmpty() {
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("\"string root\""));
    }

    @Test
    void allInvalidDaysReturnsEmpty() {
        // wrong array size
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("{\"mon\":[\"18:00\"]}"));
        // non-string values
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("{\"mon\":[18, 22]}"));
        // invalid time text
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("{\"mon\":[\"25:00\",\"02:00\"]}"));
        // equal times
        assertEquals(Optional.empty(), OpeningHoursFormatter.format("{\"mon\":[\"18:00\",\"18:00\"]}"));
    }

    @Test
    void mixedValidAndInvalidIncludesOnlyValid() {
        String json = "{\"mon\":[\"18:00\",\"02:00\"],\"tue\":[\"bad\",\"time\"]}";
        Optional<String> result = OpeningHoursFormatter.format(json);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Senin"));
        assertFalse(result.get().contains("Selasa"));
    }

    @Test
    void skippedDayIsNeverDescribedAsClosed() {
        // Only mon present
        Optional<String> result = OpeningHoursFormatter.format("{\"mon\":[\"18:00\",\"02:00\"]}");
        assertTrue(result.isPresent());
        assertFalse(result.get().toLowerCase().contains("tutup"), "Skipped day must not appear as closed");
    }

    @Test
    void dayOrderIsMondayToSunday() {
        // All days valid; output must list in mon..sun order
        Optional<String> result = OpeningHoursFormatter.format(SEED_JSON);
        assertTrue(result.isPresent());
        String out = result.get();
        int idxSenin = out.indexOf("Senin");
        int idxSelasa = out.indexOf("Selasa");
        int idxRabu = out.indexOf("Rabu");
        int idxKamis = out.indexOf("Kamis");
        int idxJumat = out.indexOf("Jumat");
        int idxSabtu = out.indexOf("Sabtu");
        int idxMinggu = out.indexOf("Minggu");
        assertTrue(idxSenin < idxSelasa);
        assertTrue(idxSelasa < idxRabu);
        assertTrue(idxRabu < idxKamis);
        assertTrue(idxKamis < idxJumat);
        assertTrue(idxJumat < idxSabtu);
        assertTrue(idxSabtu < idxMinggu);
    }
}
