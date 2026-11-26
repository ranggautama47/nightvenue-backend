package com.nightvenue.api.chat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChatIntentTests {

    @Test
    void enumValuesAndOrderAreExact() {
        ChatIntent[] values = ChatIntent.values();
        List<String> names = Arrays.stream(values).map(Enum::name).toList();
        assertEquals(List.of(
            "OPENING_HOURS",
            "VENUE_AREA",
            "MENU_INFORMATION",
            "BOTTLE_KEEPING",
            "MINIMUM_SPEND",
            "PROMOTION",
            "TABLE_AVAILABILITY",
            "RESERVATION",
            "GENERAL_VENUE_INFORMATION",
            "ADVANCED_CONVERSATION",
            "OUT_OF_SCOPE"
        ), names);
    }

    @Test
    void nineIntentsAreLocal() {
        assertTrue(ChatIntent.OPENING_HOURS.isLocal());
        assertTrue(ChatIntent.VENUE_AREA.isLocal());
        assertTrue(ChatIntent.MENU_INFORMATION.isLocal());
        assertTrue(ChatIntent.BOTTLE_KEEPING.isLocal());
        assertTrue(ChatIntent.MINIMUM_SPEND.isLocal());
        assertTrue(ChatIntent.PROMOTION.isLocal());
        assertTrue(ChatIntent.TABLE_AVAILABILITY.isLocal());
        assertTrue(ChatIntent.RESERVATION.isLocal());
        assertTrue(ChatIntent.GENERAL_VENUE_INFORMATION.isLocal());
    }

    @Test
    void advancedConversationAndOutOfScopeAreNotLocal() {
        assertFalse(ChatIntent.ADVANCED_CONVERSATION.isLocal());
        assertFalse(ChatIntent.OUT_OF_SCOPE.isLocal());
    }

    @Test
    void parseCaseSensitive() {
        assertEquals(Optional.empty(), ChatIntent.parse("opening_hours"));
        assertEquals(Optional.empty(), ChatIntent.parse("Opening_Hours"));
        assertEquals(Optional.of(ChatIntent.OPENING_HOURS), ChatIntent.parse("OPENING_HOURS"));
    }

    @Test
    void parseNullAndBlankReturnEmpty() {
        assertEquals(Optional.empty(), ChatIntent.parse(null));
        assertEquals(Optional.empty(), ChatIntent.parse(""));
        assertEquals(Optional.empty(), ChatIntent.parse("   "));
    }
}
