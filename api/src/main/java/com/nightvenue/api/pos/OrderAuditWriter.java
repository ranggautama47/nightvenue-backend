package com.nightvenue.api.pos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

    public void paymentCreated(long venueId, long actorId, long paymentId, long orderId,
                               String method, long amount, Long shiftId, Instant now) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("order_id", orderId);
        details.put("method", method);
        details.put("amount", amount);
        if (shiftId != null) details.put("shift_id", shiftId);
        write(venueId, actorId, "PAYMENT_CREATED", "PAYMENT", paymentId, details, now);
    }

    public void paymentSettled(long venueId, long paymentId, long orderId, long amount, Instant now) {
        write(venueId, null, "PAYMENT_SETTLED", "PAYMENT", paymentId,
                Map.of("order_id", orderId, "amount", amount), now);
    }

    public void paymentStatusChanged(long venueId, long paymentId, long orderId,
                                     com.nightvenue.api.payment.PaymentStatus from,
                                     com.nightvenue.api.payment.PaymentStatus to, Instant now) {
        write(venueId, null, "PAYMENT_STATUS_CHANGED", "PAYMENT", paymentId,
                Map.of("order_id", orderId, "from", from.name(), "to", to.name()), now);
    }

    public void shiftOpened(long venueId, long actorId, long shiftId, LocalDate businessDate, Instant now) {
        write(venueId, actorId, "SHIFT_OPENED", "CASHIER_SHIFT", shiftId, Map.of("business_date", businessDate.toString()), now);
    }

    public void shiftForcedReview(long venueId, long actorId, long shiftId, LocalDate businessDate, Instant now) {
        write(venueId, actorId, "SHIFT_FORCED_REVIEW", "CASHIER_SHIFT", shiftId, Map.of("business_date", businessDate.toString()), now);
    }

    public void anomaly(long venueId, String action, String entityType, long entityId,
                        Map<String, ?> details, Instant now) {
        write(venueId, null, action, entityType, entityId, details, now);
    }

    private void write(long venueId, Long actorId, String action, String entityType, long entityId,
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
