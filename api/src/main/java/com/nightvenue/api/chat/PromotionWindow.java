package com.nightvenue.api.chat;

import java.time.LocalDate;

public record PromotionWindow(long id, String title, LocalDate startsOn, LocalDate endsOn) {
}
