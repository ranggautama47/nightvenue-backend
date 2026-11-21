package com.nightvenue.api.chat;

import org.flywaydb.core.Flyway;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ChatMigrationTests {

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

    private static FluentConfiguration flyway(String schema) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema);
    }

    private String createSchema() {
        String schema = "chat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.execute("CREATE SCHEMA " + schema);
        return schema;
    }

    private void dropSchema(String schema) {
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void v13AppliesAndSchemaMatches() {
        String schema = createSchema();
        try {
            Flyway f = flyway(schema).load();
            f.migrate();
            assertEquals("13", f.info().current().getVersion().getVersion());

            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = 'venue_faqs'
                    """, Integer.class, schema));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = 'promotions'
                    """, Integer.class, schema));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = 'chat_route_logs'
                    """, Integer.class, schema));

            assertEquals(5, jdbc.queryForObject("""
                    SELECT numeric_precision FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'chat_route_logs' AND column_name = 'confidence'
                    """, Integer.class, schema));
            assertEquals(4, jdbc.queryForObject("""
                    SELECT numeric_scale FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'chat_route_logs' AND column_name = 'confidence'
                    """, Integer.class, schema));
            assertEquals(12, jdbc.queryForObject("""
                    SELECT numeric_precision FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'chat_route_logs' AND column_name = 'estimated_cost_usd'
                    """, Integer.class, schema));
            assertEquals(6, jdbc.queryForObject("""
                    SELECT numeric_scale FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'chat_route_logs' AND column_name = 'estimated_cost_usd'
                    """, Integer.class, schema));

            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'chat_route_logs'
                      AND column_name IN ('message', 'prompt', 'content', 'key', 'api_key', 'header', 'headers')
                    """, Integer.class, schema));

            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.table_constraints
                    WHERE table_schema = ? AND table_name = 'venue_faqs'
                      AND constraint_type = 'UNIQUE' AND constraint_name = 'uq_venue_faqs_venue_intent'
                    """, Integer.class, schema));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void chatRouteLogChecksAndUniqueConstraintsAreEnforced() {
        String schema = createSchema();
        try {
            flyway(schema).load().migrate();
            long venueId = insertVenue(schema, "NV-CHAT-01");
            Timestamp now = Timestamp.from(Instant.now());

            DataIntegrityViolationException route = assertThrows(DataIntegrityViolationException.class, () ->
                    jdbc.update("""
                            INSERT INTO %s.chat_route_logs
                                (venue_id, route, intent, confidence, ml_available, classifier_ms,
                                 llm_provider, llm_ms, input_tokens, output_tokens, estimated_cost_usd, created_at)
                            VALUES (?, 'INVALID', 'GENERAL', 0.5, true, 1, NULL, NULL, NULL, NULL, NULL, ?)
                            """.formatted(schema), venueId, now));
            assertConstraint(route, "ck_chat_route_logs_route");

            DataIntegrityViolationException provider = assertThrows(DataIntegrityViolationException.class, () ->
                    jdbc.update("""
                            INSERT INTO %s.chat_route_logs
                                (venue_id, route, intent, confidence, ml_available, classifier_ms,
                                 llm_provider, llm_ms, input_tokens, output_tokens, estimated_cost_usd, created_at)
                            VALUES (?, 'LLM', 'GENERAL', 0.5, true, 1, 'OPENAI', NULL, NULL, NULL, NULL, ?)
                            """.formatted(schema), venueId, now));
            assertConstraint(provider, "ck_chat_route_logs_llm_provider");

            DataIntegrityViolationException promoDates = assertThrows(DataIntegrityViolationException.class, () ->
                    jdbc.update("""
                            INSERT INTO %s.promotions (venue_id, title, description, starts_on, ends_on, is_active)
                            VALUES (?, 'Bad Range', 'x', DATE '2026-11-10', DATE '2026-11-09', true)
                            """.formatted(schema), venueId));
            assertConstraint(promoDates, "ck_promotions_date_range");

            jdbc.update("""
                    INSERT INTO %s.venue_faqs (venue_id, intent, answer_template, is_active)
                    VALUES (?, 'GENERAL_VENUE_INFORMATION', 'hello', true)
                    """.formatted(schema), venueId);
            DataIntegrityViolationException unique = assertThrows(DataIntegrityViolationException.class, () ->
                    jdbc.update("""
                            INSERT INTO %s.venue_faqs (venue_id, intent, answer_template, is_active)
                            VALUES (?, 'GENERAL_VENUE_INFORMATION', 'other', true)
                            """.formatted(schema), venueId));
            assertConstraint(unique, "uq_venue_faqs_venue_intent");
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void foreignKeyActionsMatchChatSchema() {
        String schema = createSchema();
        try {
            flyway(schema).load().migrate();
            long venueId = insertVenue(schema, "NV-CHAT-FK");
            Timestamp now = Timestamp.from(Instant.now());

            jdbc.update("""
                    INSERT INTO %s.venue_faqs (venue_id, intent, answer_template, is_active)
                    VALUES (?, 'BOTTLE_KEEPING', 'faq', true)
                    """.formatted(schema), venueId);
            jdbc.update("""
                    INSERT INTO %s.promotions (venue_id, title, description, starts_on, ends_on, is_active)
                    VALUES (?, 'Promo', 'desc', DATE '2026-11-01', DATE '2027-11-01', true)
                    """.formatted(schema), venueId);
            jdbc.update("""
                    INSERT INTO %s.chat_route_logs
                        (venue_id, route, intent, confidence, ml_available, classifier_ms,
                         llm_provider, llm_ms, input_tokens, output_tokens, estimated_cost_usd, created_at)
                    VALUES (?, 'LOCAL', 'GENERAL_VENUE_INFORMATION', 0.9000, true, 5, NULL, NULL, NULL, NULL, NULL, ?)
                    """.formatted(schema), venueId, now);

            jdbc.update("DELETE FROM " + schema + ".venues WHERE id = ?", venueId);

            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".venue_faqs", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".promotions", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".chat_route_logs", Integer.class));
            assertNull(jdbc.queryForObject("SELECT venue_id FROM " + schema + ".chat_route_logs", Long.class));
        } finally {
            dropSchema(schema);
        }
    }

    private long insertVenue(String schema, String code) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO %s.venues (code, name, address, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, void_manager_approval_threshold, opening_hours, is_active, created_at, updated_at)
                VALUES (?, ?, 'Demo Address', 'Asia/Jakarta', 6, 0, 0, 500000, '{}'::jsonb, true, ?, ?)
                RETURNING id
                """.formatted(schema), Long.class, code, "Chat Venue " + code, now, now);
    }

    private void assertConstraint(Throwable throwable, String constraintName) {
        StringBuilder text = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append('\n');
            }
            for (StackTraceElement element : current.getStackTrace()) {
                text.append(element.toString()).append('\n');
            }
            current = current.getCause();
        }
        assertTrue(text.toString().contains(constraintName),
                "Expected constraint '" + constraintName + "' but got: " + throwable);
    }
}
