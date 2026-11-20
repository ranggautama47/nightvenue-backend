package com.nightvenue.api.forecast;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

abstract class ForecastTestSupport {
    static final String INTERNAL_TOKEN = "forecast-test-token-never-return";
    static final Instant DEFAULT_NOW = Instant.parse("2026-10-24T00:00:00Z");
    static final LocalDate DEFAULT_AS_OF = LocalDate.of(2026, 10, 23);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdjustableClock clock;

    String fixture;
    long venueId;
    long otherVenueId;
    long ingredientId;
    long otherVenueIngredientId;
    long inventoryItemId;
    long adminId;
    long managerId;
    long cashierId;

    abstract ConfigurableMlStub stub();

    @BeforeEach
    void createForecastFixtures() {
        stub().reset();
        clock.set(DEFAULT_NOW);
        fixture = "F9C3C-" + UUID.randomUUID().toString().substring(0, 8);
        venueId = createVenue(fixture + "-A");
        otherVenueId = createVenue(fixture + "-B");
        adminId = createUser("ADMIN");
        managerId = createUser("MANAGER");
        cashierId = createUser("CASHIER");
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", managerId, venueId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", cashierId, venueId);
        ingredientId = createIngredient("main", "ML");
        otherVenueIngredientId = createIngredient("other", "GRAM");
        long locationId = createLocation(venueId, "A");
        long otherLocationId = createLocation(otherVenueId, "B");
        inventoryItemId = createInventoryItem(locationId, ingredientId);
        createInventoryItem(otherLocationId, otherVenueIngredientId);
    }

    @AfterEach
    void cleanForecastFixtures() {
        if (fixture == null) {
            return;
        }
        jdbc.update("DELETE FROM stock_forecasts WHERE venue_id IN "
                + "(SELECT id FROM venues WHERE code LIKE ?)", fixture + "%");
        jdbc.update("""
                DELETE FROM inventory_transactions WHERE inventory_item_id IN (
                    SELECT id FROM inventory_items WHERE location_id IN (
                        SELECT id FROM stock_locations WHERE venue_id IN (?, ?)))
                """, venueId, otherVenueId);
        jdbc.update("DELETE FROM inventory_items WHERE location_id IN "
                + "(SELECT id FROM stock_locations WHERE venue_id IN (?, ?))", venueId, otherVenueId);
        jdbc.update("DELETE FROM stock_locations WHERE venue_id IN (?, ?)", venueId, otherVenueId);
        jdbc.update("DELETE FROM ingredients WHERE sku LIKE ?", fixture + "%");
        jdbc.update("DELETE FROM user_venues WHERE user_id IN "
                + "(SELECT id FROM users WHERE email LIKE ?)", fixture.toLowerCase() + "%");
        jdbc.update("DELETE FROM users WHERE email LIKE ?", fixture.toLowerCase() + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", fixture + "%");
    }

    String bearer(String role) {
        long userId = switch (role) {
            case "ADMIN" -> adminId;
            case "MANAGER" -> managerId;
            case "CASHIER" -> cashierId;
            default -> throw new IllegalArgumentException("Unknown fixture role");
        };
        Instant issuedAt = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("nightvenue")
                .subject(Long.toString(userId))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claim("role", role)
                .build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    String body(long venue, long ingredient, Integer horizon) {
        return "{\"venueId\":" + venue + ",\"ingredientId\":" + ingredient
                + (horizon == null ? "}" : ",\"horizonDays\":" + horizon + "}");
    }

    MvcResult postForecast(String role, long venue, long ingredient, Integer horizon) throws Exception {
        return mvc.perform(post("/api/v1/forecasts")
                        .header("Authorization", bearer(role))
                        .contentType("application/json")
                        .content(body(venue, ingredient, horizon)))
                .andReturn();
    }

    void assertNoForecasts() {
        org.junit.jupiter.api.Assertions.assertEquals(0, forecastCount());
    }

    int forecastCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_forecasts WHERE venue_id IN (?, ?)",
                Integer.class, venueId, otherVenueId);
    }

    void addHistory(LocalDate firstDate, int dayCount, LocalDate gapDate, LocalDate negativeDate) {
        for (int index = 0; index < dayCount; index++) {
            LocalDate date = firstDate.plusDays(index);
            if (date.equals(gapDate)) {
                continue;
            }
            if (date.equals(negativeDate)) {
                insertLedger(inventoryItemId, "VOID_RETURN", "5.000", date);
            } else {
                insertLedger(inventoryItemId, "SALE_DEDUCT", "-1.000", date);
            }
        }
    }

    void addSingleHistoryDay(LocalDate date) {
        insertLedger(inventoryItemId, "SALE_DEDUCT", "-1.000", date);
    }

    private void insertLedger(long itemId, String type, String delta, LocalDate date) {
        jdbc.update("""
                INSERT INTO inventory_transactions
                    (inventory_item_id, type, delta, balance_after, business_date, reference_type,
                     reference_id, reason, created_by, created_at)
                VALUES (?, ?, ?, 100, ?, 'MANUAL', NULL, NULL, ?, ?)
                """, itemId, type, new BigDecimal(delta), date, managerId, Timestamp.from(clock.instant()));
    }

    private long createVenue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, is_active,
                                    created_at, updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, true, now(), now()) RETURNING id
                """, Long.class, code, code);
    }

    private long createUser(String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, version, created_at, updated_at)
                VALUES (?, 'fixture-hash', ?, ?, 0, now(), now()) RETURNING id
                """, Long.class, fixture.toLowerCase() + "-" + role.toLowerCase() + "@example.test",
                fixture + " " + role, role);
    }

    private long createIngredient(String suffix, String unit) {
        return jdbc.queryForObject("""
                INSERT INTO ingredients (sku, name, unit, is_active)
                VALUES (?, ?, ?, true) RETURNING id
                """, Long.class, fixture + "-" + suffix, fixture + " " + suffix, unit);
    }

    private long createLocation(long venue, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO stock_locations (venue_id, name, type) VALUES (?, ?, 'BAR') RETURNING id
                """, Long.class, venue, fixture + "-" + suffix);
    }

    private long createInventoryItem(long location, long ingredient) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version, updated_at)
                VALUES (?, ?, 0, 0, 0, now()) RETURNING id
                """, Long.class, location, ingredient);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        AdjustableClock forecastTestClock() {
            return new AdjustableClock(DEFAULT_NOW);
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> instant;

        AdjustableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
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
