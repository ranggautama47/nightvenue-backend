package com.nightvenue.api.common.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Generic idempotency repository reusable by Phase 5A (ORDER_OPEN),
 * Phase 5B, and Phase 5C. Scope is passed as a parameter.
 *
 * <p>Retention note: rows older than 24 h are eligible for cleanup by a
 * scheduled job (deferred to a later phase). After cleanup, replay protection
 * for that key is no longer guaranteed.
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempts to reserve an idempotency slot.
     *
     * @return {@code true} if the row was inserted (new request);
     *         {@code false} if the row already existed (replay candidate).
     */
    public boolean reserve(UUID key, String scope, long userId,
                           String requestHash, Instant createdAt) {
        int rows = jdbc.update("""
                INSERT INTO idempotency_keys (key, scope, user_id, request_hash, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_idem DO NOTHING
                """,
                key, scope, userId, requestHash, Timestamp.from(createdAt));
        return rows > 0;
    }

    /**
     * Reads the stored hash, response status, and response body for a previously reserved key.
     *
     * @return the row if it exists
     */
    public Optional<IdempotencyRow> find(UUID key, String scope, long userId) {
        return jdbc.query("""
                SELECT request_hash, response_status, response_body::text
                FROM idempotency_keys
                WHERE key = ? AND scope = ? AND user_id = ?
                """,
                (rs, row) -> new IdempotencyRow(
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body")),
                key, scope, userId)
                .stream().findFirst();
    }

    /**
     * Persists the response snapshot so that future replays can return it.
     */
    public void complete(UUID key, String scope, long userId,
                         int status, String bodyJson) {
        int updated = jdbc.update("""
                UPDATE idempotency_keys
                SET response_status = ?, response_body = ?::jsonb
                WHERE key = ? AND scope = ? AND user_id = ?
                """,
                status, bodyJson, key, scope, userId);
        if (updated != 1) {
            throw new IllegalStateException("Expected to complete exactly one idempotency record, updated " + updated);
        }
    }

    public record IdempotencyRow(String requestHash, Integer responseStatus, String responseBody) {}
}
