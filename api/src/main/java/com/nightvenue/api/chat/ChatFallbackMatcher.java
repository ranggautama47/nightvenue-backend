package com.nightvenue.api.chat;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic keyword/regex fallback matcher per ADR-012.
 * Priority order: BOTTLE_KEEPING_POLICY, MINIMUM_SPEND, OPENING_HOURS, VENUE_ADDRESS, VENUE_AREA.
 */
@Component
public class ChatFallbackMatcher {

    private static final List<PatternCategory> RULES = List.of(
        new PatternCategory(
            Pattern.compile("bottle\\s*keeping|simpan\\s+botol|titip\\s+botol|keep\\s+botol|penyimpanan\\s+botol"),
            ChatFallbackCategory.BOTTLE_KEEPING_POLICY
        ),
        new PatternCategory(
            Pattern.compile("minimum\\s*spend|minimal\\s*spend|min\\.?\\s*spend|minimum\\s+belanja|minimal\\s+belanja"),
            ChatFallbackCategory.MINIMUM_SPEND
        ),
        new PatternCategory(
            Pattern.compile("jam\\s+(buka|operasional|tutup)|opening\\s+hours|open\\s+hours|kapan\\s+(buka|tutup)|buka\\s+jam|jam\\s+berapa\\s+(buka|tutup)|what\\s+time\\b.*\\b(open|close)"),
            ChatFallbackCategory.OPENING_HOURS
        ),
        new PatternCategory(
            Pattern.compile("alamat|address|lokasi|location"),
            ChatFallbackCategory.VENUE_ADDRESS
        ),
        new PatternCategory(
            Pattern.compile("\\barea\\b|\\bzona\\b|\\bzone\\b"),
            ChatFallbackCategory.VENUE_AREA
        )
    );

    public Optional<ChatFallbackCategory> match(String message) {
        if (message == null || message.isBlank()) return Optional.empty();
        String normalized = message.toLowerCase(Locale.ROOT);
        for (PatternCategory rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                return Optional.of(rule.category());
            }
        }
        return Optional.empty();
    }

    private record PatternCategory(Pattern pattern, ChatFallbackCategory category) {}
}
