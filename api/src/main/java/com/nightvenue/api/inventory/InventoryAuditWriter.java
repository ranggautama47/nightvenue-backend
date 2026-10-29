package com.nightvenue.api.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

@Service
public class InventoryAuditWriter {
    private final JdbcTemplate jdbc;

    public InventoryAuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void adjustment(
            long venueId,
            long actorUserId,
            long inventoryItemId,
            String type,
            BigDecimal delta,
            BigDecimal previousQuantity,
            BigDecimal newQuantity,
            String reason,
            String ipAddress,
            Instant createdAt) {
        String details = reason == null
                ? "jsonb_build_object('type', ?, 'delta', ?, 'previousQuantity', ?, 'newQuantity', ?)"
                : "jsonb_build_object('type', ?, 'delta', ?, 'previousQuantity', ?, 'newQuantity', ?, 'reason', ?)";
        if (reason == null) {
            jdbc.update("""
                INSERT INTO audit_logs
                    (venue_id, actor_user_id, action, entity_type, entity_id, details, ip_address, created_at)
                VALUES (?, ?, 'INVENTORY_ADJUST', 'INVENTORY_ITEM', ?,
                    %s,
                    ?, ?)
                """.formatted(details), venueId, actorUserId, inventoryItemId, type,
                    delta.toPlainString(), previousQuantity.toPlainString(), newQuantity.toPlainString(),
                    ipAddress, Timestamp.from(createdAt));
        } else {
            jdbc.update("""
                    INSERT INTO audit_logs
                        (venue_id, actor_user_id, action, entity_type, entity_id, details, ip_address, created_at)
                    VALUES (?, ?, 'INVENTORY_ADJUST', 'INVENTORY_ITEM', ?,
                        %s,
                        ?, ?)
                    """.formatted(details), venueId, actorUserId, inventoryItemId, type,
                    delta.toPlainString(), previousQuantity.toPlainString(), newQuantity.toPlainString(), reason,
                    ipAddress, Timestamp.from(createdAt));
        }
    }
}
