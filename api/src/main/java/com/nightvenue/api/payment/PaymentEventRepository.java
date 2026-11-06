package com.nightvenue.api.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class PaymentEventRepository {
    private final JdbcTemplate jdbc;

    public PaymentEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(long paymentId, String provider, String eventKey,
                      PaymentEventResult result, String payloadSnapshot, Instant receivedAt) {
        return jdbc.update("""
                INSERT INTO payment_events (payment_id, provider, event_key, result, payload_snapshot, received_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT ON CONSTRAINT uq_payment_events_provider_event DO NOTHING
                """, paymentId, provider, eventKey, result.name(), payloadSnapshot, Timestamp.from(receivedAt));
    }
}
