package com.nightvenue.api.pos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class OrderLifecycleRepository {
    private final JdbcTemplate jdbc;

    public OrderLifecycleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long voidApprovalThreshold(long venueId) {
        return jdbc.queryForObject("SELECT void_manager_approval_threshold FROM venues WHERE id = ?", Long.class, venueId);
    }

    public long insertVoidLog(long venueId, long orderId, long itemId, java.util.UUID batchId,
                              long voidedBy, Long approvedBy, String reason, String disposition,
                              long itemValue, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO void_logs (venue_id, order_id, order_item_id, batch_id, voided_by,
                    approved_by, reason, disposition, item_value, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, venueId, orderId, itemId, batchId, voidedBy, approvedBy,
                reason, disposition, itemValue, Timestamp.from(now));
    }

    public int transitionItemsToVoided(long orderId, Instant now) {
        Integer alreadyVoided = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE order_id = ? AND status = 'VOIDED'", Integer.class, orderId);
        if (alreadyVoided != null && alreadyVoided != 0) throw new IllegalStateException("Order already contains VOIDED items");
        return jdbc.update("UPDATE order_items SET status = 'VOIDED', updated_at = ? WHERE order_id = ? AND status IN ('ORDERED','SERVED')",
                Timestamp.from(now), orderId);
    }
}
