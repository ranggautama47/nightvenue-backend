package com.nightvenue.api.bottle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public class BottleRepository {

    private static final String LABEL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public BottleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean vaultExistsForOrderItem(long orderItemId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM bottle_vaults WHERE source_order_item_id = ?)",
                Boolean.class,
                orderItemId);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<CustomerInfo> findCustomer(long customerId) {
        return jdbc.query("""
                SELECT id, age_verified
                FROM customers
                WHERE id = ?
                """, (rs, rowNum) -> new CustomerInfo(
                rs.getLong("id"),
                rs.getBoolean("age_verified")), customerId).stream().findFirst();
    }

    public Optional<OrderItemProductInfo> findOrderItemProductInfo(long orderItemId) {
        return jdbc.query("""
                SELECT oi.id, oi.order_id, oi.product_id, oi.item_type, oi.quantity, oi.status,
                       p.is_bottle_keepable, p.bottle_volume_ml
                FROM order_items oi
                JOIN products p ON oi.product_id = p.id
                WHERE oi.id = ?
                """, (rs, rowNum) -> new OrderItemProductInfo(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("product_id"),
                rs.getString("item_type"),
                rs.getInt("quantity"),
                rs.getString("status"),
                rs.getBoolean("is_bottle_keepable"),
                rs.getObject("bottle_volume_ml", Integer.class)), orderItemId).stream().findFirst();
    }

    public VaultRecord insertVaultWithRetry(
            long venueId,
            long customerId,
            long productId,
            long sourceOrderItemId,
            int volumeMl,
            LocalDate expiryDate,
            String qrTokenHash,
            Instant now) {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String labelCode = generateLabelCode();
            Timestamp timestamp = Timestamp.from(now);
            var results = jdbc.query("""
                    INSERT INTO bottle_vaults (
                        venue_id, customer_id, product_id, source_order_item_id,
                        label_code, qr_token_hash, original_volume_ml, remaining_volume_ml,
                        status, version, stored_at, expiry_date, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?, ?, ?)
                    ON CONFLICT (label_code) DO NOTHING
                    RETURNING id, venue_id, customer_id, product_id, source_order_item_id,
                              label_code, original_volume_ml, remaining_volume_ml, status,
                              stored_at, expiry_date
                    """, (rs, rowNum) -> new VaultRecord(
                    rs.getLong("id"),
                    rs.getLong("venue_id"),
                    rs.getLong("customer_id"),
                    rs.getLong("product_id"),
                    rs.getLong("source_order_item_id"),
                    rs.getString("label_code"),
                    rs.getInt("original_volume_ml"),
                    rs.getInt("remaining_volume_ml"),
                    rs.getString("status"),
                    rs.getTimestamp("stored_at").toInstant(),
                    rs.getDate("expiry_date").toLocalDate()),
                    venueId, customerId, productId, sourceOrderItemId,
                    labelCode, qrTokenHash, volumeMl, volumeMl,
                    timestamp, Date.valueOf(expiryDate), timestamp, timestamp);

            if (!results.isEmpty()) {
                return results.get(0);
            }
        }
        throw new IllegalStateException("Failed to generate unique bottle label code after " + maxRetries + " attempts");
    }

    public void insertTransaction(
            long vaultId,
            Long orderItemId,
            String type,
            int volumeDeltaMl,
            int remainingVolumeMl,
            Long performedBy,
            boolean ownerConfirmed,
            Instant now) {
        jdbc.update("""
                INSERT INTO bottle_vault_transactions (
                    vault_id, order_item_id, type, volume_delta_ml, remaining_after_ml,
                    performed_by, owner_confirmed, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                vaultId, orderItemId, type, volumeDeltaMl, remainingVolumeMl,
                performedBy, ownerConfirmed, Timestamp.from(now));
    }

    public static boolean isSourceOrderItemUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().contains("uq_vault_source_item")) {
                return true;
            }
        }
        return false;
    }

    private static String generateLabelCode() {
        StringBuilder sb = new StringBuilder("BV-");
        for (int i = 0; i < 12; i++) {
            sb.append(LABEL_ALPHABET.charAt(RANDOM.nextInt(LABEL_ALPHABET.length())));
        }
        return sb.toString();
    }

    public record CustomerInfo(long id, boolean ageVerified) {}

    public record OrderItemProductInfo(
            long id,
            long orderId,
            long productId,
            String itemType,
            int quantity,
            String status,
            boolean isBottleKeepable,
            Integer bottleVolumeMl
    ) {}

    public record VaultRecord(
            long id,
            long venueId,
            long customerId,
            long productId,
            long sourceOrderItemId,
            String labelCode,
            int originalVolumeMl,
            int remainingVolumeMl,
            String status,
            Instant storedAt,
            LocalDate expiryDate
    ) {}
}

