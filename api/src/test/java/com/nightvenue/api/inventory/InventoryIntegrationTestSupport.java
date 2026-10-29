package com.nightvenue.api.inventory;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, InventoryIntegrationTestSupport.ClockConfiguration.class})
abstract class InventoryIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionTemplate transactions;
    @Autowired InventoryService inventory;
    @Autowired InventoryDeductService deduct;
    @Autowired AdjustableClock clock;

    String fixture;
    long venueId;
    long otherVenueId;
    long barLocationId;
    long warehouseLocationId;
    long otherBarLocationId;
    long ingredientId;
    long inventoryItemId;

    @BeforeEach
    void createInventoryFixtures() {
        fixture = "N4C-" + UUID.randomUUID().toString().substring(0, 12);
        clock.set(Instant.parse("2026-10-23T19:30:00Z"));
        venueId = createVenue(fixture + "-venue-a");
        otherVenueId = createVenue(fixture + "-venue-b");
        jdbc.update("""
                INSERT INTO user_venues (user_id, venue_id)
                SELECT id, ? FROM users WHERE email IN ('manager@nightvenue.demo', 'cashier@nightvenue.demo')
                ON CONFLICT DO NOTHING
                """, venueId);
        barLocationId = createLocation(venueId, fixture + "-bar", "BAR");
        warehouseLocationId = createLocation(venueId, fixture + "-warehouse", "WAREHOUSE");
        otherBarLocationId = createLocation(otherVenueId, fixture + "-other-bar", "BAR");
        ingredientId = createIngredient(fixture + "-ingredient", true);
        inventoryItemId = createInventoryItem(barLocationId, ingredientId, "0.000", "0.000", 0);
    }

    @AfterEach
    void cleanupInventoryFixtures() {
        if (fixture == null) {
            return;
        }
        jdbc.update("DELETE FROM audit_logs WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("""
                DELETE FROM inventory_transactions
                WHERE inventory_item_id IN (
                    SELECT i.id FROM inventory_items i
                    JOIN stock_locations l ON l.id = i.location_id
                    WHERE l.venue_id IN (?, ?)
                )
                """, venueId, otherVenueId);
        jdbc.update("""
                DELETE FROM inventory_items
                WHERE location_id IN (SELECT id FROM stock_locations WHERE venue_id IN (?, ?))
                """, venueId, otherVenueId);
        jdbc.update("DELETE FROM stock_locations WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM ingredients WHERE sku LIKE ?", fixture + "-%");
        jdbc.update("DELETE FROM venues WHERE id IN (?, ?)", venueId, otherVenueId);
    }

    long createVenue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, is_active, created_at, updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now())
                RETURNING id
                """, Long.class, code, code);
    }

    long createLocation(long venue, String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO stock_locations (venue_id, name, type) VALUES (?, ?, ?) RETURNING id
                """, Long.class, venue, name, type);
    }

    long createIngredient(String sku, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO ingredients (sku, name, unit, is_active)
                VALUES (?, ?, 'ML', ?) RETURNING id
                """, Long.class, sku, sku, active);
    }

    long createInventoryItem(long location, long ingredient, String quantity, String reorderLevel, long version) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version, updated_at)
                VALUES (?, ?, ?, ?, ?, now()) RETURNING id
                """, Long.class, location, ingredient, new BigDecimal(quantity),
                new BigDecimal(reorderLevel), version);
    }

    String bearer(String role) {
        String email = switch (role) {
            case "ADMIN" -> "admin@nightvenue.demo";
            case "MANAGER" -> "manager@nightvenue.demo";
            case "CASHIER" -> "cashier@nightvenue.demo";
            case "CUSTOMER" -> "customer@nightvenue.demo";
            default -> throw new IllegalArgumentException("Unknown test role");
        };
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("nightvenue")
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("role", role)
                .build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    long demoUserId(String role) {
        String email = switch (role) {
            case "ADMIN" -> "admin@nightvenue.demo";
            case "MANAGER" -> "manager@nightvenue.demo";
            case "CASHIER" -> "cashier@nightvenue.demo";
            case "CUSTOMER" -> "customer@nightvenue.demo";
            default -> throw new IllegalArgumentException("Unknown test role");
        };
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    String adjustJson(String type, String delta, String reason, Long expectedVersion) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("delta", delta);
        body.put("reason", reason);
        body.put("expectedVersion", expectedVersion);
        return objectMapper.writeValueAsString(body);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        AdjustableClock inventoryTestClock() {
            return new AdjustableClock(Instant.parse("2026-10-23T19:30:00Z"));
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> instant;

        AdjustableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
