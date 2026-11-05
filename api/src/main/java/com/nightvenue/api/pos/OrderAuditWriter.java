package com.nightvenue.api.pos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Service
public class OrderAuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OrderAuditWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void voidOrder(long venueId, long actorId, long orderId, String reason,
                          String disposition, java.util.UUID batchId, int itemCount, Instant now) {
        write(venueId, actorId, "ORDER_VOID", "ORDER", orderId,
                Map.of("reason", reason, "disposition", disposition, "batch_id", batchId.toString(), "item_count", itemCount), now);
    }

    public void serve(long venueId, long actorId, long itemId, Instant now) {
        write(venueId, actorId, "ORDER_ITEM_SERVE", "ORDER_ITEM", itemId, Map.of(), now);
    }

    private void write(long venueId, long actorId, String action, String entityType, long entityId,
                       Map<String, ?> details, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO audit_logs (venue_id, actor_user_id, action, entity_type, entity_id, details, created_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, venueId, actorId, action, entityType, entityId,
                    mapper.writeValueAsString(details), Timestamp.from(now));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write order audit", e);
        }
    }
}
