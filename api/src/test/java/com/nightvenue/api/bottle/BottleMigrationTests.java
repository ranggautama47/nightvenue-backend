package com.nightvenue.api.bottle;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class BottleMigrationTests {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.4-alpine"));

    static JdbcTemplate jdbc;

    @BeforeAll
    static void init() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = jdbc(postgres.getJdbcUrl());
    }

    private static JdbcTemplate jdbc(String jdbcUrl) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword()));
    }

    private static FluentConfiguration flyway(String schema, String jdbcUrl) {
        return Flyway.configure().dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").schemas(schema).defaultSchema(schema);
    }

    private String createSchema() {
        String schema = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.execute("CREATE SCHEMA " + schema);
        return schema;
    }

    private void dropSchema(String schema) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void tablesIndexesAndConstraintsExist() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'bottle_vaults'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'bottle_vault_transactions'", Integer.class));

        // Indexes
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'ix_bottle_vaults_customer_status'", Integer.class));
        String partialDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_bottle_vaults_expiry_active'", String.class);
        assertNotNull(partialDef);
        assertTrue(partialDef.contains("WHERE") && partialDef.contains("(status)::text = 'ACTIVE'::text" ) || partialDef.contains("status = 'ACTIVE'"));

        // FK delete rules
        assertEquals("RESTRICT", jdbc.queryForObject("""
                SELECT confdeltype::text FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                WHERE t.relname = 'bottle_vaults' AND c.conname = 'bottle_vaults_source_order_item_id_fkey'
                """, String.class).replace("r", "RESTRICT"));

        assertEquals("RESTRICT", jdbc.queryForObject("""
                SELECT confdeltype::text FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                WHERE t.relname = 'bottle_vault_transactions' AND c.conname = 'bottle_vault_transactions_order_item_id_fkey'
                """, String.class).replace("r", "RESTRICT"));

        assertEquals("RESTRICT", jdbc.queryForObject("""
                SELECT confdeltype::text FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                WHERE t.relname = 'bottle_vault_transactions' AND c.conname = 'bottle_vault_transactions_performed_by_fkey'
                """, String.class).replace("r", "RESTRICT"));
    }

    @Test
    void actorConstraintCheck() {
        Fixtures fix = createFixtures(null);
        long vaultId = insertVault(fix.venueId, fix.customerId, fix.productId, fix.orderItemId, "LBL-A1", "hash-a1", 750, 750, "ACTIVE");

        // EXPIRE with performed_by = NULL succeeds
        long expireTxId = jdbc.queryForObject("""
                INSERT INTO bottle_vault_transactions (vault_id, type, volume_delta_ml, remaining_after_ml, performed_by, created_at)
                VALUES (?, 'EXPIRE', 0, 750, NULL, now()) RETURNING id
                """, Long.class, vaultId);
        assertTrue(expireTxId > 0);

        // DEPOSIT with performed_by = NULL fails 23514
        DataIntegrityViolationException eDep = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.execute("INSERT INTO bottle_vault_transactions (vault_id, type, volume_delta_ml, remaining_after_ml, performed_by, created_at) VALUES (" + vaultId + ", 'DEPOSIT', 750, 750, NULL, now())"));
        assertEquals("23514", sqlState(eDep));

        // REDEEM with performed_by = NULL fails 23514
        DataIntegrityViolationException eRed = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.execute("INSERT INTO bottle_vault_transactions (vault_id, type, volume_delta_ml, remaining_after_ml, performed_by, owner_confirmed, created_at) VALUES (" + vaultId + ", 'REDEEM', -250, 500, NULL, true, now())"));
        assertEquals("23514", sqlState(eRed));

        // DEPOSIT/REDEEM with valid user succeeds
        long depTxId = jdbc.queryForObject("""
                INSERT INTO bottle_vault_transactions (vault_id, type, volume_delta_ml, remaining_after_ml, performed_by, created_at)
                VALUES (?, 'DEPOSIT', 750, 750, ?, now()) RETURNING id
                """, Long.class, vaultId, fix.userId);
        assertTrue(depTxId > 0);

        long redTxId = jdbc.queryForObject("""
                INSERT INTO bottle_vault_transactions (vault_id, type, volume_delta_ml, remaining_after_ml, performed_by, owner_confirmed, created_at)
                VALUES (?, 'REDEEM', -250, 500, ?, true, now()) RETURNING id
                """, Long.class, vaultId, fix.userId);
        assertTrue(redTxId > 0);
    }

    @Test
    void redeemOwnerConfirmedConstraintCheck() {
        Fixtures fix = createFixtures(null);
        long vaultId = insertVault(fix.venueId, fix.customerId, fix.productId, fix.orderItemId, "LBL-B1", "hash-b1", 750, 750, "ACTIVE");

        // REDEEM + owner_confirmed=FALSE -> 23514
        DataIntegrityViolationException eFalse = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.execute("INSERT INTO bottle_vault_transactions (vault_id, type, volume_delta_ml, remaining_after_ml, performed_by, owner_confirmed, created_at) VALUES (" + vaultId + ", 'REDEEM', -100, 650, " + fix.userId + ", false, now())"));
        assertEquals("23514", sqlState(eFalse));

        // REDEEM + explicit owner_confirmed=NULL -> 23502 (NOT 23514)
        DataIntegrityViolationException eNull = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.execute("INSERT INTO bottle_vault_transactions (vault_id, type, volume_delta_ml, remaining_after_ml, performed_by, owner_confirmed, created_at) VALUES (" + vaultId + ", 'REDEEM', -100, 650, " + fix.userId + ", NULL, now())"));
        assertEquals("23502", sqlState(eNull));
    }

    @Test
    void volumeConstraintChecks() {
        Fixtures fix = createFixtures(null);

        // remaining < 0 -> 23514
        DataIntegrityViolationException eNeg = assertThrows(DataIntegrityViolationException.class, () ->
                insertVault(fix.venueId, fix.customerId, fix.productId, fix.orderItemId, "LBL-C1", "hash-c1", 750, -1, "ACTIVE"));
        assertEquals("23514", sqlState(eNeg));

        // remaining > original -> 23514
        DataIntegrityViolationException eExceed = assertThrows(DataIntegrityViolationException.class, () ->
                insertVault(fix.venueId, fix.customerId, fix.productId, fix.orderItemId, "LBL-C2", "hash-c2", 750, 800, "ACTIVE"));
        assertEquals("23514", sqlState(eExceed));

        // original <= 0 -> 23514
        DataIntegrityViolationException eOrig = assertThrows(DataIntegrityViolationException.class, () ->
                insertVault(fix.venueId, fix.customerId, fix.productId, fix.orderItemId, "LBL-C3", "hash-c3", 0, 0, "ACTIVE"));
        assertEquals("23514", sqlState(eOrig));
    }

    @Test
    void uniquenessConstraintsCheck() {
        Fixtures fix = createFixtures(null);
        long v1 = insertVault(fix.venueId, fix.customerId, fix.productId, fix.orderItemId, "LBL-D1", "hash-d1", 750, 750, "ACTIVE");
        assertTrue(v1 > 0);

        // duplicate source_order_item_id -> 23505 on uq_vault_source_item
        DataIntegrityViolationException eDupItem = assertThrows(DataIntegrityViolationException.class, () ->
                insertVault(fix.venueId, fix.customerId, fix.productId, fix.orderItemId, "LBL-D2", "hash-d2", 750, 750, "ACTIVE"));
        assertEquals("23505", sqlState(eDupItem));
        assertTrue(eDupItem.getMessage().contains("uq_vault_source_item"));

        // two vaults with NULL source_order_item_id allowed
        long vNull1 = insertVault(fix.venueId, fix.customerId, fix.productId, null, "LBL-D3", "hash-d3", 750, 750, "ACTIVE");
        long vNull2 = insertVault(fix.venueId, fix.customerId, fix.productId, null, "LBL-D4", "hash-d4", 750, 750, "ACTIVE");
        assertTrue(vNull1 > 0 && vNull2 > 0);

        // duplicate label_code -> 23505
        DataIntegrityViolationException eDupLabel = assertThrows(DataIntegrityViolationException.class, () ->
                insertVault(fix.venueId, fix.customerId, fix.productId, null, "LBL-D1", "hash-unique-1", 750, 750, "ACTIVE"));
        assertEquals("23505", sqlState(eDupLabel));

        // duplicate qr_token_hash -> 23505
        DataIntegrityViolationException eDupHash = assertThrows(DataIntegrityViolationException.class, () ->
                insertVault(fix.venueId, fix.customerId, fix.productId, null, "LBL-unique-1", "hash-d1", 750, 750, "ACTIVE"));
        assertEquals("23505", sqlState(eDupHash));
    }

    @Test
    void statusAndTypeCheckConstraints() {
        Fixtures fix = createFixtures(null);

        // invalid status -> 23514
        DataIntegrityViolationException eStatus = assertThrows(DataIntegrityViolationException.class, () ->
                insertVault(fix.venueId, fix.customerId, fix.productId, null, "LBL-E1", "hash-e1", 750, 750, "INVALID"));
        assertEquals("23514", sqlState(eStatus));

        long v = insertVault(fix.venueId, fix.customerId, fix.productId, null, "LBL-E2", "hash-e2", 750, 750, "ACTIVE");

        // invalid tx type -> 23514
        DataIntegrityViolationException eType = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.execute("INSERT INTO bottle_vault_transactions (vault_id, type, volume_delta_ml, remaining_after_ml, performed_by, created_at) VALUES (" + v + ", 'UNKNOWN', 0, 750, " + fix.userId + ", now())"));
        assertEquals("23514", sqlState(eType));
    }

    @Test
    void migrationEmptyToV10AndV9DataToV10() {
        String schema = createSchema();
        try {
            // 1. Empty to V10
            Flyway f = flyway(schema, postgres.getJdbcUrl()).load();
            f.migrate();
            assertEquals("12", f.info().current().getVersion().getVersion());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'bottle_vaults'", Integer.class, schema));

            // 2. Test V9 data migration
            String schema2 = createSchema();
            try {
                Flyway fV9 = flyway(schema2, postgres.getJdbcUrl()).target(MigrationVersion.fromVersion("9")).load();
                fV9.migrate();
                assertEquals("9", fV9.info().current().getVersion().getVersion());

                // Seed some V9 data in schema2
                Fixtures fix = createFixtures(schema2);
                assertTrue(fix.orderItemId > 0);

                // Now migrate schema2 to latest (V10)
                Flyway fV10 = flyway(schema2, postgres.getJdbcUrl()).load();
                fV10.migrate();
                assertEquals("12", fV10.info().current().getVersion().getVersion());

                // Verify previous data in schema2 is intact
                Integer orderItemCount = jdbc.queryForObject("SELECT COUNT(*) FROM " + schema2 + ".order_items WHERE id = ?", Integer.class, fix.orderItemId);
                assertEquals(1, orderItemCount);
            } finally {
                dropSchema(schema2);
            }
        } finally {
            dropSchema(schema);
        }
    }

    private long insertVault(long venueId, long custId, long prodId, Long orderItemId, String lbl, String hash, int orig, int rem, String status) {
        return jdbc.queryForObject("""
                INSERT INTO bottle_vaults (venue_id, customer_id, product_id, source_order_item_id, label_code, qr_token_hash, original_volume_ml, remaining_volume_ml, stored_at, expiry_date, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), CURRENT_DATE + 30, ?, 0, now(), now()) RETURNING id
                """, Long.class, venueId, custId, prodId, orderItemId, lbl, hash, orig, rem, status);
    }

    private Fixtures createFixtures(String schemaPrefix) {
        String pfx = (schemaPrefix == null || schemaPrefix.isBlank()) ? "" : schemaPrefix + ".";
        String s = UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        long venueId = jdbc.queryForObject("INSERT INTO " + pfx + "venues (code, name, timezone, business_day_cutoff_hour, is_active, created_at, updated_at) VALUES ('V-" + s + "', 'Ven', 'Asia/Jakarta', 6, true, now(), now()) RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO " + pfx + "users (email, password_hash, full_name, role, is_active, version, created_at, updated_at) VALUES ('u-" + s + "@x.com', 'h', 'U', 'CASHIER', true, 0, now(), now()) RETURNING id", Long.class);
        long custId = jdbc.queryForObject("INSERT INTO " + pfx + "customers (full_name, age_verified, age_verified_at, age_verified_by, created_at, updated_at) VALUES ('Cust " + s + "', true, now(), " + userId + ", now(), now()) RETURNING id", Long.class);
        long prodId = jdbc.queryForObject("INSERT INTO " + pfx + "products (venue_id, sku, name, price, is_bottle_keepable, bottle_volume_ml, is_active, version, created_at, updated_at) VALUES (" + venueId + ", 'SKU-" + s + "', 'Vodka', 500000, true, 750, true, 0, now(), now()) RETURNING id", Long.class);
        long orderId = jdbc.queryForObject("INSERT INTO " + pfx + "orders (venue_id, order_number, cashier_id, customer_id, business_date, status, subtotal, total_due, opened_at, created_at, updated_at, version) VALUES (" + venueId + ", 'O-" + s + "', " + userId + ", " + custId + ", CURRENT_DATE, 'PAID', 500000, 500000, now(), now(), now(), 0) RETURNING id", Long.class);
        long orderItemId = jdbc.queryForObject("INSERT INTO " + pfx + "order_items (order_id, product_id, item_type, product_name_snapshot, unit_price_snapshot, quantity, line_total, status, created_at, updated_at) VALUES (" + orderId + ", " + prodId + ", 'PRODUCT', 'Vodka', 500000, 1, 500000, 'ORDERED', now(), now()) RETURNING id", Long.class);
        return new Fixtures(venueId, userId, custId, prodId, orderId, orderItemId);
    }

    private static String sqlState(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException s) return s.getSQLState();
            t = t.getCause();
        }
        return "UNKNOWN";
    }

    record Fixtures(long venueId, long userId, long customerId, long productId, long orderId, long orderItemId) {}
}

