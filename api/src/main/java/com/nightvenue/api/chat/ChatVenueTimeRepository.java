package com.nightvenue.api.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatVenueTimeRepository {
    private final JdbcTemplate jdbc;

    public ChatVenueTimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VenueTimeContext> findActiveVenueTime(long venueId) {
        List<VenueTimeContext> rows = jdbc.query("""
                SELECT timezone, business_day_cutoff_hour
                FROM venues
                WHERE id = ? AND is_active = true
                """, (rs, row) -> {
            String tz = rs.getString("timezone");
            int cutoff = rs.getInt("business_day_cutoff_hour");
            try {
                ZoneId zone = ZoneId.of(tz);
                if (cutoff < 0 || cutoff > 23) return null;
                return new VenueTimeContext(zone, cutoff);
            } catch (DateTimeException e) {
                return null;
            }
        }, venueId);
        return rows.stream().filter(c -> c != null).findFirst();
    }
}
