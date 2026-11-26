package com.nightvenue.api.chat;

import java.util.Optional;

public enum ChatIntent {
    OPENING_HOURS,
    VENUE_AREA,
    MENU_INFORMATION,
    BOTTLE_KEEPING,
    MINIMUM_SPEND,
    PROMOTION,
    TABLE_AVAILABILITY,
    RESERVATION,
    GENERAL_VENUE_INFORMATION,
    ADVANCED_CONVERSATION,
    OUT_OF_SCOPE;

    public boolean isLocal() {
        return ordinal() < ADVANCED_CONVERSATION.ordinal();
    }

    public static Optional<ChatIntent> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
