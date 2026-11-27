package com.nightvenue.api.chat;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChatFallbackMatcherTests {

    private final ChatFallbackMatcher matcher = new ChatFallbackMatcher();

    // --- BOTTLE_KEEPING_POLICY ---

    @Test
    void bottleKeepingIndonesian() {
        assertEquals(Optional.of(ChatFallbackCategory.BOTTLE_KEEPING_POLICY),
            matcher.match("simpan botol whiskey"));
        assertEquals(Optional.of(ChatFallbackCategory.BOTTLE_KEEPING_POLICY),
            matcher.match("titip botol di sini"));
        assertEquals(Optional.of(ChatFallbackCategory.BOTTLE_KEEPING_POLICY),
            matcher.match("penyimpanan botol berlaku?"));
    }

    @Test
    void bottleKeepingEnglish() {
        assertEquals(Optional.of(ChatFallbackCategory.BOTTLE_KEEPING_POLICY),
            matcher.match("bottle keeping policy"));
        assertEquals(Optional.of(ChatFallbackCategory.BOTTLE_KEEPING_POLICY),
            matcher.match("keep botol disini"));
        assertEquals(Optional.of(ChatFallbackCategory.BOTTLE_KEEPING_POLICY),
            matcher.match("bottlekeeping available?"));
    }

    // --- MINIMUM_SPEND ---

    @Test
    void minimumSpendIndonesian() {
        assertEquals(Optional.of(ChatFallbackCategory.MINIMUM_SPEND),
            matcher.match("minimum belanja berapa?"));
        assertEquals(Optional.of(ChatFallbackCategory.MINIMUM_SPEND),
            matcher.match("minimal belanja di area VIP"));
        assertEquals(Optional.of(ChatFallbackCategory.MINIMUM_SPEND),
            matcher.match("min. spend untuk meja VIP"));
    }

    @Test
    void minimumSpendEnglish() {
        assertEquals(Optional.of(ChatFallbackCategory.MINIMUM_SPEND),
            matcher.match("minimum spend here?"));
        assertEquals(Optional.of(ChatFallbackCategory.MINIMUM_SPEND),
            matcher.match("minimal spend requirement"));
        assertEquals(Optional.of(ChatFallbackCategory.MINIMUM_SPEND),
            matcher.match("min spend vip table"));
    }

    // --- OPENING_HOURS ---

    @Test
    void openingHoursIndonesian() {
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("jam buka venue ini?"));
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("kapan buka?"));
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("jam berapa tutup?"));
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("jam operasional hari ini"));
    }

    @Test
    void openingHoursEnglish() {
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("opening hours please"));
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("open hours on friday"));
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("what time do you open?"));
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("what time close"));
    }

    // --- VENUE_ADDRESS ---

    @Test
    void venueAddressIndonesian() {
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_ADDRESS),
            matcher.match("alamat venue ini?"));
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_ADDRESS),
            matcher.match("lokasi di mana?"));
    }

    @Test
    void venueAddressEnglish() {
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_ADDRESS),
            matcher.match("address of this venue"));
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_ADDRESS),
            matcher.match("what is the location?"));
    }

    // --- VENUE_AREA ---

    @Test
    void venueAreaIndonesian() {
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_AREA),
            matcher.match("ada area apa saja?"));
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_AREA),
            matcher.match("zona VIP ada tidak?"));
    }

    @Test
    void venueAreaEnglish() {
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_AREA),
            matcher.match("what area is available"));
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_AREA),
            matcher.match("any zone for large groups?"));
    }

    // --- Case-insensitive ---

    @Test
    void caseInsensitive() {
        assertEquals(Optional.of(ChatFallbackCategory.BOTTLE_KEEPING_POLICY),
            matcher.match("BOTTLE KEEPING INFO"));
        assertEquals(Optional.of(ChatFallbackCategory.OPENING_HOURS),
            matcher.match("JAM BUKA"));
        assertEquals(Optional.of(ChatFallbackCategory.VENUE_ADDRESS),
            matcher.match("ALAMAT"));
    }

    // --- Unmatched ---

    @Test
    void unmatchedMessagesReturnEmpty() {
        assertEquals(Optional.empty(), matcher.match("siapa presiden indonesia"));
        assertEquals(Optional.empty(), matcher.match("rekomendasi menu terbaik untuk pasangan"));
    }

    // --- Null and blank ---

    @Test
    void nullAndBlankReturnEmpty() {
        assertEquals(Optional.empty(), matcher.match(null));
        assertEquals(Optional.empty(), matcher.match(""));
        assertEquals(Optional.empty(), matcher.match("   "));
    }

    // --- Priority ---

    @Test
    void priorityMinimumSpendBeforeArea() {
        // "minimum spend area vip" → MINIMUM_SPEND wins over VENUE_AREA
        assertEquals(Optional.of(ChatFallbackCategory.MINIMUM_SPEND),
            matcher.match("minimum spend area vip"));
    }

    @Test
    void priorityBottleKeepingBeforeOpeningHours() {
        // "jam buka bottle keeping" → BOTTLE_KEEPING_POLICY wins over OPENING_HOURS
        assertEquals(Optional.of(ChatFallbackCategory.BOTTLE_KEEPING_POLICY),
            matcher.match("jam buka bottle keeping"));
    }
}
