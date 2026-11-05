package com.nightvenue.api.pos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class OrderLifecycleMigrationTests {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.4-alpine"));

    @Test void migrationChainCreatesVoidConstraintsAndIndexes() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='void_logs'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname IN ('uq_void_once_per_item','ix_void_logs_order_id','ix_inventory_transactions_ref')", Integer.class));
        String definition = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='uq_orders_active_table'", String.class);
        assertTrue(definition.contains("OPEN") && definition.contains("PARTIALLY_PAID"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='void_logs' AND column_name='disposition' AND column_default IS NOT NULL", Integer.class));
    }

    @Test void voidLogUniqueConstraintRejectsDuplicateItem() {
        JdbcTemplate jdbc = migratedJdbc();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long venue = jdbc.queryForObject("""
                INSERT INTO venues (code,name,timezone,business_day_cutoff_hour,is_active,created_at,updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, "MIG-" + suffix, "MIG-" + suffix);
        long user = jdbc.queryForObject("""
                INSERT INTO users (email,password_hash,full_name,role,is_active,version,created_at,updated_at)
                VALUES (?, 'h', 'Mig User', 'MANAGER', true, 0, now(), now()) RETURNING id
                """, Long.class, "mig-" + suffix + "@test.local");
        long product = jdbc.queryForObject("""
                INSERT INTO products (venue_id,sku,name,price,is_bottle_keepable,is_active,version,created_at,updated_at)
                VALUES (?, ?, 'Mig Product', 1000, false, true, 0, now(), now()) RETURNING id
                """, Long.class, venue, "MIG-" + suffix);
        long order = jdbc.queryForObject("""
                INSERT INTO orders (venue_id,order_number,cashier_id,business_date,status,subtotal,total_due,
                                    opened_at,created_at,updated_at,version)
                VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', 1000, 1000, now(), now(), now(), 0) RETURNING id
                """, Long.class, venue, "MIG-" + suffix, user);
        long item = jdbc.queryForObject("""
                INSERT INTO order_items (order_id,product_id,item_type,product_name_snapshot,unit_price_snapshot,
                                         quantity,line_total,status,created_at,updated_at)
                VALUES (?, ?, 'PRODUCT', 'Mig Product', 1000, 1, 1000, 'ORDERED', now(), now()) RETURNING id
                """, Long.class, order, product);
        String insert = """
                INSERT INTO void_logs (venue_id,order_id,order_item_id,batch_id,voided_by,reason,disposition,item_value,created_at)
                VALUES (?, ?, ?, ?, ?, 'duplicate', 'WASTED', 1000, now())
                """;
        jdbc.update(insert, venue, order, item, UUID.randomUUID(), user);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, venue, order, item, UUID.randomUUID(), user));
    }

    @Test void voidLogForeignKeysRestrictAndApprovedByNullable() {
        JdbcTemplate jdbc = migratedJdbc();
        Map<String, String> actions = new LinkedHashMap<>();
        jdbc.query("""
                SELECT child_col.attname AS column_name, c.confdeltype::text AS delete_action
                FROM pg_constraint c
                JOIN pg_class child ON child.oid = c.conrelid
                JOIN pg_attribute child_col ON child_col.attrelid = child.oid AND child_col.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND child.relname = 'void_logs'
                """, (rs, rowNum) -> Map.entry(rs.getString("column_name"), rs.getString("delete_action")))
                .forEach(entry -> actions.put(entry.getKey(), entry.getValue()));
        assertEquals(Map.of("venue_id", "r", "order_id", "r", "order_item_id", "r",
                "voided_by", "r", "approved_by", "r"), actions);
        assertEquals("YES", jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name='void_logs' AND column_name='approved_by'
                """, String.class));
        assertEquals("NO", jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name='void_logs' AND column_name='order_item_id'
                """, String.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint WHERE conname='uq_void_once_per_item' AND contype='u'
                """, Integer.class));
    }

    private JdbcTemplate migratedJdbc() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
}
