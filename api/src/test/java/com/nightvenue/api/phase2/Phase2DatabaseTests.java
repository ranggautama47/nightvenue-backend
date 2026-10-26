package com.nightvenue.api.phase2;

import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.seed.DemoDataSeeder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.nightvenue.api.TestcontainersConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class Phase2DatabaseTests {
    private static final List<String> SEED_TABLES = List.of("venues", "users", "user_venues", "customers",
            "product_categories", "products", "ingredients", "recipes", "recipe_items", "stock_locations",
            "inventory_items", "inventory_transactions", "venue_tables", "audit_logs", "idempotency_keys");

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired DemoDataSeeder seeder;
    @Autowired BusinessDateService businessDates;
    @Autowired DataSource dataSource;
    @Autowired java.time.Clock clock;

    @Test
    void migrationsValidateAndCreateOnlyPhaseTwoTables() {
        flyway.validate();
        List<String> expected = new java.util.ArrayList<>(SEED_TABLES);
        expected.add("flyway_schema_history");
        List<String> actual = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name", String.class);
        assertEquals(expected.stream().sorted().toList(), actual);
    }

    @Test
    void foreignKeyDeleteActionsMatchDatabaseMatrixAndStayInPhaseTwo() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("user_venues.user_id", "c"), Map.entry("user_venues.venue_id", "c"),
                Map.entry("customers.user_id", "n"), Map.entry("customers.age_verified_by", "r"),
                Map.entry("products.venue_id", "r"), Map.entry("products.category_id", "r"),
                Map.entry("recipes.product_id", "c"), Map.entry("recipe_items.recipe_id", "c"),
                Map.entry("recipe_items.ingredient_id", "r"), Map.entry("stock_locations.venue_id", "r"),
                Map.entry("inventory_items.location_id", "r"), Map.entry("inventory_items.ingredient_id", "r"),
                Map.entry("inventory_transactions.inventory_item_id", "r"), Map.entry("inventory_transactions.created_by", "r"),
                Map.entry("venue_tables.venue_id", "r"), Map.entry("audit_logs.venue_id", "n"),
                Map.entry("audit_logs.actor_user_id", "n"), Map.entry("idempotency_keys.user_id", "c"));
        Map<String, String> actual = new LinkedHashMap<>();
        jdbc.query("""
                SELECT child.relname || '.' || child_col.attname AS fk_column, c.confdeltype::text AS delete_action
                FROM pg_constraint c
                JOIN pg_class child ON child.oid=c.conrelid
                JOIN pg_attribute child_col ON child_col.attrelid=child.oid AND child_col.attnum=c.conkey[1]
                JOIN pg_class parent ON parent.oid=c.confrelid
                WHERE c.contype='f' AND child.relnamespace='public'::regnamespace
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> actual.put(rs.getString("fk_column"), rs.getString("delete_action")));
        assertEquals(expected, actual);
        assertTrue(actual.keySet().stream().noneMatch(key -> key.startsWith("orders.") || key.startsWith("payments.")
                || key.startsWith("bottle_vaults.") || key.startsWith("cashier_shifts.")));
    }

    @Test
    void phaseTwoChecksAreEnforcedByPostgres() {
        long venueId = id("SELECT id FROM venues LIMIT 1");
        long userId = id("SELECT id FROM users LIMIT 1");
        long locationId = id("SELECT id FROM stock_locations WHERE type='BAR' LIMIT 1");
        long ingredientId = id("SELECT id FROM ingredients LIMIT 1");
        long categoryId = id("SELECT id FROM product_categories LIMIT 1");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO inventory_items (location_id,ingredient_id,quantity,reorder_level,version) VALUES (?,?, -1,0,0)", locationId, ingredientId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO stock_locations (venue_id,name,type) VALUES (?, 'BAR duplicate', 'BAR')", venueId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO venue_tables (venue_id,code,capacity,status,is_active,version) VALUES (?, 'T01', 2, 'AVAILABLE', true, 0)", venueId));

        UUID key = UUID.randomUUID();
        String insertIdem = "INSERT INTO idempotency_keys (key,scope,user_id,request_hash,created_at) VALUES (?, 'PAYMENT_CREATE', ?, ?, ?)";
        jdbc.update(insertIdem, key, userId, "a".repeat(64), Timestamp.from(Instant.now()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insertIdem, key, userId, "a".repeat(64), Timestamp.from(Instant.now())));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO customers (full_name,phone,age_verified,created_at,updated_at) VALUES ('Invalid Guest','+620000009999',true,now(),now())"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO products (venue_id,category_id,sku,name,price,is_bottle_keepable,bottle_volume_ml,is_active,version,created_at,updated_at)
                VALUES (?, ?, 'invalid-bottle', 'Invalid Bottle', 1, true, NULL, true, 0, now(), now())
                """, venueId, categoryId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO venue_tables (venue_id,code,capacity,status,is_active,version) VALUES (?, 'RESERVED-TABLE', 2, 'RESERVED', true, 0)", venueId));
    }

    @Test
    void reseedingDoesNotOverwriteManualInventoryQuantity() {
        long itemId = id("SELECT id FROM inventory_items ORDER BY id LIMIT 1");
        BigDecimal original = jdbc.queryForObject("SELECT quantity FROM inventory_items WHERE id=?", BigDecimal.class, itemId);
        BigDecimal manual = original.add(BigDecimal.ONE);
        jdbc.update("UPDATE inventory_items SET quantity=? WHERE id=?", manual, itemId);
        seeder.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(manual, jdbc.queryForObject("SELECT quantity FROM inventory_items WHERE id=?", BigDecimal.class, itemId));
        jdbc.update("UPDATE inventory_items SET quantity=? WHERE id=?", original, itemId);
    }

    @Test
    void failedLedgerInsertRollsBackAndRetryCompletesItemHistory() {
        long locationId = id("SELECT id FROM stock_locations WHERE type='BAR' ORDER BY id LIMIT 1");
        long ingredientId = id("SELECT id FROM ingredients ORDER BY id LIMIT 1");
        long itemId = jdbc.queryForObject("SELECT id FROM inventory_items WHERE location_id=? AND ingredient_id=?", Long.class, locationId, ingredientId);
        jdbc.update("DELETE FROM inventory_transactions WHERE inventory_item_id=? AND reference_type='SEED'", itemId);
        jdbc.update("UPDATE inventory_items SET quantity=0 WHERE id=?", itemId);
        jdbc.execute("CREATE FUNCTION fail_phase2_seed() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.inventory_item_id=" + itemId + " THEN RAISE EXCEPTION 'injected seed failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_phase2_seed BEFORE INSERT ON inventory_transactions FOR EACH ROW EXECUTE FUNCTION fail_phase2_seed()");
        assertThrows(Exception.class, () -> seeder.run(new DefaultApplicationArguments(new String[0])));
        jdbc.execute("DROP TRIGGER fail_phase2_seed ON inventory_transactions");
        jdbc.execute("DROP FUNCTION fail_phase2_seed()");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE inventory_item_id=? AND reference_type='SEED'", Integer.class, itemId));
        seeder.run(new DefaultApplicationArguments(new String[0]));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE inventory_item_id=? AND reference_type='SEED'", Integer.class, itemId) > 0);
    }

    @Test
    void seederIsIdempotentAndItsLedgerBalances() {
        Map<String, Long> before = counts();
        List<String> ledgerBefore = ledgerSnapshot();
        seeder.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(before, counts());
        assertEquals(ledgerBefore, ledgerSnapshot());

        assertEquals(2, count("venues"));
        assertEquals(4, count("users"));
        assertEquals(24, count("venue_tables"));
        assertEquals(25, count("ingredients"));
        assertEquals(40, count("products"));
        assertEquals(count("inventory_items"), jdbc.queryForObject("SELECT COUNT(DISTINCT inventory_item_id) FROM inventory_transactions WHERE reference_type='SEED'", Long.class));

        List<String> ledgerDates = jdbc.query("SELECT id,business_date FROM inventory_transactions ORDER BY id",
                (rs, row) -> rs.getLong("id") + "|" + rs.getDate("business_date"));
        jdbc.update("UPDATE venues SET business_day_cutoff_hour=8 WHERE id=(SELECT id FROM venues ORDER BY id LIMIT 1)");
        assertEquals(ledgerDates, jdbc.query("SELECT id,business_date FROM inventory_transactions ORDER BY id",
                (rs, row) -> rs.getLong("id") + "|" + rs.getDate("business_date")));
        jdbc.update("UPDATE venues SET business_day_cutoff_hour=6");

        Long badBalances = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT balance_after, delta,
                           COALESCE(LAG(balance_after) OVER (PARTITION BY inventory_item_id ORDER BY id), 0) prior
                    FROM inventory_transactions
                ) ledger WHERE balance_after <> prior + delta OR balance_after < 0
                """, Long.class);
        assertEquals(0, badBalances);
        Long quantityMismatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_items ii
                JOIN (SELECT inventory_item_id, SUM(delta) total FROM inventory_transactions GROUP BY inventory_item_id) t
                  ON t.inventory_item_id=ii.id
                WHERE ii.quantity <> t.total
                """, Long.class);
        assertEquals(0, quantityMismatches);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE reference_type='SEED' AND created_by IS NOT NULL", Long.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_transactions t
                JOIN inventory_items ii ON ii.id=t.inventory_item_id
                JOIN stock_locations sl ON sl.id=ii.location_id
                WHERE t.type='SALE_DEDUCT' AND sl.type <> 'BAR'
                """, Integer.class));
        assertEquals(90, jdbc.queryForObject("SELECT COUNT(DISTINCT business_date) FROM inventory_transactions WHERE reference_type='SEED'", Integer.class));

        BigDecimal weekendAverage = jdbc.queryForObject("SELECT AVG(-delta) FROM inventory_transactions WHERE type='SALE_DEDUCT' AND EXTRACT(ISODOW FROM business_date) IN (5,6)", BigDecimal.class);
        BigDecimal weekdayAverage = jdbc.queryForObject("SELECT AVG(-delta) FROM inventory_transactions WHERE type='SALE_DEDUCT' AND EXTRACT(ISODOW FROM business_date) BETWEEN 1 AND 4", BigDecimal.class);
        assertTrue(weekendAverage.compareTo(weekdayAverage) > 0, "weekend sales should exceed weekday sales");

        jdbc.query("SELECT created_at,business_date FROM inventory_transactions", rs -> {
            Instant createdAt = rs.getTimestamp("created_at").toInstant();
            LocalDate stored = rs.getDate("business_date").toLocalDate();
            assertEquals(stored, businessDates.businessDate(createdAt, ZoneId.of("Asia/Jakarta"), 6));
        });
        assertFalse(ledgerBefore.isEmpty());
    }

    @Test
    void fixedSeedProducesIdenticalLedgersInTwoFreshDatabases() {
        HikariDataSource source = (HikariDataSource) dataSource;
        String database = "phase2_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + database);
        String secondUrl = source.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
        DriverManagerDataSource secondDataSource = new DriverManagerDataSource(secondUrl, source.getUsername(), source.getPassword());
        JdbcTemplate secondJdbc = new JdbcTemplate(secondDataSource);
        try {
            Flyway.configure().dataSource(secondDataSource).locations("classpath:db/migration").load().migrate();
            DemoDataSeeder secondSeeder = new DemoDataSeeder(secondJdbc, clock,
                    new BusinessDateService(clock));
            new TransactionTemplate(new DataSourceTransactionManager(secondDataSource))
                    .executeWithoutResult(status -> secondSeeder.run(new DefaultApplicationArguments(new String[0])));
            assertEquals(ledgerSnapshot(), ledgerSnapshot(secondJdbc));
        } finally {
            jdbc.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }

    private Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : SEED_TABLES) result.put(table, count(table));
        return result;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private List<String> ledgerSnapshot() {
        return ledgerSnapshot(jdbc);
    }

    private List<String> ledgerSnapshot(JdbcTemplate template) {
        LocalDate refDate = businessDates.currentBusinessDate(ZoneId.of("Asia/Jakarta"), 6);
        return template.query("""
                SELECT t.inventory_item_id, t.type, t.delta, t.balance_after,
                       (t.business_date - ?) AS days_from_ref,
                       t.reference_type, t.reference_id, t.reason, t.created_by,
                       t.created_at
                FROM inventory_transactions t
                ORDER BY t.inventory_item_id, t.type, t.delta, t.balance_after, t.created_at
                """,
                (rs, row) -> rs.getLong("inventory_item_id") + "|" + rs.getString("type") + "|" + rs.getBigDecimal("delta") + "|"
                        + rs.getBigDecimal("balance_after") + "|" + rs.getLong("days_from_ref") + "|" + rs.getString("reference_type")
                        + "|" + rs.getObject("reference_id") + "|" + rs.getString("reason") + "|" + rs.getObject("created_by") + "|" + rs.getTimestamp("created_at"),
                refDate);
    }
}
