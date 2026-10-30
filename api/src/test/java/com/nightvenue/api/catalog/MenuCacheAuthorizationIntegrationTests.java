package com.nightvenue.api.catalog;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class MenuCacheAuthorizationIntegrationTests {
    private static final String PRODUCTS = "/api/v1/products";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired StringRedisTemplate redis;

    private String fixture;
    private long venueAId;
    private long venueBId;
    private long venueBCashierId;
    private long cashierId;
    private String productBSku;

    @BeforeEach
    void createFixtures() {
        fixture = "CACHE-" + UUID.randomUUID().toString().substring(0, 8);
        venueAId = createVenue(fixture + "-venue-a");
        venueBId = createVenue(fixture + "-venue-b");
        venueBCashierId = createUser(fixture.toLowerCase() + "-cashier-b@test.local", "CASHIER");
        cashierId = createUser(fixture.toLowerCase() + "-cashier@test.local", "CASHIER");
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", venueBCashierId, venueBId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", cashierId, venueAId);
        createProduct(venueAId, fixture + "-product-a");
        productBSku = fixture + "-product-b";
        createProduct(venueBId, productBSku);
    }

    @AfterEach
    void removeFixtures() {
        if (venueAId > 0) {
            redis.delete("menu:" + venueAId);
        }
        if (venueBId > 0) {
            redis.delete("menu:" + venueBId);
            jdbc.update("DELETE FROM products WHERE venue_id = ?", venueBId);
        }
        if (venueAId > 0) {
            jdbc.update("DELETE FROM products WHERE venue_id = ?", venueAId);
        }
        if (fixture != null) {
            jdbc.update("DELETE FROM users WHERE email IN (?, ?)",
                    fixture.toLowerCase() + "-cashier-b@test.local",
                    fixture.toLowerCase() + "-cashier@test.local");
            jdbc.update("DELETE FROM venues WHERE code IN (?, ?)",
                    fixture + "-venue-a", fixture + "-venue-b");
        }
    }

    @Test
    void cashierCannotReadAnotherVenuesCachedProducts() throws Exception {
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueBId))
                        .header("Authorization", bearer(venueBCashierId, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value(productBSku));

        org.junit.jupiter.api.Assertions.assertNotNull(redis.opsForValue().get("menu:" + venueBId));

        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueBId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Venue not found"));
    }

    private long createVenue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, created_at, updated_at)
                VALUES (?, ?, now(), now()) RETURNING id
                """, Long.class, code, code);
    }

    private long createUser(String email, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, is_active, version, created_at, updated_at)
                VALUES (?, 'test-hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, email, email, role);
    }

    private void createProduct(long venueId, String sku) {
        jdbc.update("""
                INSERT INTO products
                    (venue_id, sku, name, price, is_bottle_keepable, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, 100, false, true, 0, now(), now())
                """, venueId, sku, sku);
    }

    private String bearer(long userId, String role) {
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
}
