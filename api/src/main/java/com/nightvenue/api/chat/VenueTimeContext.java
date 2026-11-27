package com.nightvenue.api.chat;

import java.time.ZoneId;

public record VenueTimeContext(ZoneId zone, int cutoffHour) {
}
