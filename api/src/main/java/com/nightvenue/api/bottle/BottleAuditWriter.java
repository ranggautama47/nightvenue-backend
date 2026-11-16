package com.nightvenue.api.bottle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BottleAuditWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BottleAuditWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void bottleDeposited(
            long venueId,
            long actorId,
            long vaultId,
            long orderId,
            long orderItemId,
            long customerId,
            long productId,
            String labelCode,
            int originalVolumeMl,
            LocalDate expiryDate,
            Instant now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("order_id", orderId);
        details.put("order_item_id", orderItemId);
        details.put("customer_id", customerId);
        details.put("product_id", productId);
        details.put("label_code", labelCode);
        details.put("original_volume_ml", originalVolumeMl);
        details.put("expiry_date", expiryDate.toString());

        write(venueId, actorId, "BOTTLE_DEPOSIT", "BOTTLE_VAULT", vaultId, details, now);
    }

    public void bottleRedeemed(
            long venueId,
            long actorId,
            long vaultId,
            long orderId,
            long orderItemId,
            int redeemedVolumeMl,
            int remainingVolumeMl,
            Instant now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("order_id", orderId);
        details.put("order_item_id", orderItemId);
        details.put("redeemed_volume_ml", redeemedVolumeMl);
        details.put("remaining_volume_ml", remainingVolumeMl);
        write(venueId, actorId, "BOTTLE_REDEEM", "BOTTLE_VAULT", vaultId, details, now);
    }

    private void write(
            long venueId,
            Long actorId,
            String action,
            String entityType,
            long entityId,
            Map<String, ?> details,
            Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO audit_logs (venue_id, actor_user_id, action, entity_type, entity_id, details, created_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                    """,
                    venueId,
                    actorId,
                    action,
                    entityType,
                    entityId,
                    mapper.writeValueAsString(details),
                    Timestamp.from(now));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write bottle audit log", e);
        }
    }
}

