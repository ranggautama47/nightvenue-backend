package com.nightvenue.api.catalog;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class MenuCacheIntegrationTests {

    private static final String PRODUCTS = "/api/v1/products";
    private static final String CATEGORIES = "/api/v1/categories";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired ProductService productService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean StringRedisTemplate redis;

    private String fixture;
    private long venueAId;
    private long venueBId;
    private long categoryId;
    private long productId;
    private long cashierId;
    private long adminId;
    private long managerId;
    private long cashierBId;

    @BeforeEach
    void createFixtures() {
        fixture = "MC-" + UUID.randomUUID().toString().substring(0, 8);

        venueAId = jdbc.queryForObject("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, void_manager_approval_threshold, is_active, created_at, updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, 0, 0, 0, true, now(), now()) RETURNING id
                """, Long.class, fixture + "-venue-a", fixture + "-venue-a");

        venueBId = jdbc.queryForObject("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, void_manager_approval_threshold, is_active, created_at, updated_at)
                VALUES (?, ?, 'Asia/Jakarta', 6, 0, 0, 0, true, now(), now()) RETURNING id
                """, Long.class, fixture + "-venue-b", fixture + "-venue-b");

        categoryId = jdbc.queryForObject("""
                INSERT INTO product_categories (name, sort_order) VALUES (?, 1) RETURNING id
                """, Long.class, fixture + "-category");

        productId = jdbc.queryForObject("""
                INSERT INTO products
                    (venue_id, category_id, sku, name, price, is_bottle_keepable, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 100, false, true, 0, now(), now()) RETURNING id
                """, Long.class, venueAId, categoryId, fixture + "-sku", fixture + "-product");

        cashierId = createUser(fixture.toLowerCase() + "-cashier@test.local", "CASHIER");
        adminId = createUser(fixture.toLowerCase() + "-admin@test.local", "ADMIN");
        managerId = createUser(fixture.toLowerCase() + "-manager@test.local", "MANAGER");
        cashierBId = createUser(fixture.toLowerCase() + "-cashier-b@test.local", "CASHIER");

        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", cashierId, venueAId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", adminId, venueAId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", managerId, venueAId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", cashierBId, venueBId);

        redis.delete("menu:" + venueAId);
        redis.delete("menu:" + venueBId);
    }

    @AfterEach
    void cleanup() {
        redis.delete("menu:" + venueAId);
        redis.delete("menu:" + venueBId);

        jdbc.update("DELETE FROM products WHERE venue_id IN (?, ?)", venueAId, venueBId);
        jdbc.update("DELETE FROM product_categories WHERE name LIKE ?", fixture + "-%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", fixture + "-%");
        jdbc.update("DELETE FROM users WHERE email LIKE ?", fixture.toLowerCase() + "-%@test.local");
    }

    // ===== 1. CASHIER cache miss -> populates Redis key =====
    @Test
    void cashierCacheMissPopulatesRedisKey() throws Exception {
        String cacheKey = "menu:" + venueAId;
        assertNull(redis.opsForValue().get(cacheKey), "Cache should be empty before request");

        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value(fixture + "-sku"));

        String cached = redis.opsForValue().get(cacheKey);
        assertNotNull(cached, "Cache key should be populated after CASHIER request");
        assertTrue(cached.contains(fixture + "-sku"), "Cached payload should contain product data");
    }

    // ===== 2. CASHIER cache hit -> second request returns cached payload =====
    @Test
    void cashierCacheHitReturnsCachedPayload() throws Exception {
        String cacheKey = "menu:" + venueAId;

        // First request - populate cache
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());

        String firstPayload = redis.opsForValue().get(cacheKey);
        assertNotNull(firstPayload);

        // Second request - should hit cache
        String secondResponse = mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String secondPayload = redis.opsForValue().get(cacheKey);
        assertEquals(firstPayload, secondPayload, "Cached value should remain unchanged on cache hit");
    }

    // ===== 3. Redis unavailable -> CASHIER falls back to DB =====
    @Test
    void redisUnavailableCashierFallsBackToDatabase() throws Exception {
        String cacheKey = "menu:" + venueAId;

        // Populate the cache normally while Redis is still reachable.
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        assertNotNull(redis.opsForValue().get(cacheKey));

        // Simulate a Redis outage for this one request only. The real
        // StringRedisTemplate is a Mockito spy, so other tests keep using Redis.
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> failingOps = mock(ValueOperations.class);
        when(failingOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        doThrow(new RedisConnectionFailureException("Redis unavailable"))
                .when(failingOps).set(anyString(), anyString(), any(Duration.class));
        doReturn(failingOps).when(redis).opsForValue();

        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value(fixture + "-sku"));

        verify(failingOps).get(cacheKey);
        assertNotNull(jdbc.queryForObject(
                "SELECT sku FROM products WHERE venue_id = ? AND sku = ?",
                String.class, venueAId, fixture + "-sku"),
                "The response must contain database-backed product data");
    }

    // ===== 4. Cache deserialization failure -> falls back to DB =====
    @Test
    void cacheDeserializationFailureFallsBackToDatabase() throws Exception {
        String cacheKey = "menu:" + venueAId;

        // Insert malformed JSON into Redis
        redis.opsForValue().set(cacheKey, "{malformed json}");

        // Request should still succeed via database fallback
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value(fixture + "-sku"));

        // Cache should be repaired with valid data
        String repaired = redis.opsForValue().get(cacheKey);
        assertNotNull(repaired);
        assertTrue(repaired.contains(fixture + "-sku"));
    }

    // ===== 5. Product create -> cache evicted for that venue =====
    @Test
    void productCreateEvictsCacheForVenue() throws Exception {
        String cacheKey = "menu:" + venueAId;

        // Populate cache first
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        assertNotNull(redis.opsForValue().get(cacheKey));

        // Create new product as ADMIN
        String newSku = fixture + "-new-product";
        mvc.perform(post(PRODUCTS).header("Authorization", bearer(adminId, "ADMIN"))
                        .contentType("application/json")
                        .content(productJson(venueAId, categoryId, newSku, fixture + "-new-product", 200, false, null)))
                .andExpect(status().isCreated());

        // Cache should be evicted
        assertNull(redis.opsForValue().get(cacheKey), "Cache should be evicted after product create");

        // Next CASHIER request should reflect new product
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sku == '" + newSku + "')]").exists());
    }

    // ===== 6. Product update -> cache evicted for that venue =====
    @Test
    void productUpdateEvictsCacheForVenue() throws Exception {
        String cacheKey = "menu:" + venueAId;

        // Populate cache
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        assertNotNull(redis.opsForValue().get(cacheKey));

        // Update product as MANAGER
        mvc.perform(put(PRODUCTS + "/" + productId).header("Authorization", bearer(managerId, "MANAGER"))
                        .contentType("application/json")
                        .content(productJson(venueAId, categoryId, fixture + "-updated-sku", fixture + "-updated", 150, false, null)))
                .andExpect(status().isOk());

        // Cache should be evicted
        assertNull(redis.opsForValue().get(cacheKey), "Cache should be evicted after product update");

        // Next CASHIER request should reflect updated product
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sku == '" + fixture + "-updated-sku')]").exists());
    }

    // ===== 7. Product deactivate -> cache evicted for that venue =====
    @Test
    void productDeactivateEvictsCacheForVenue() throws Exception {
        String cacheKey = "menu:" + venueAId;

        // Populate cache
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        assertNotNull(redis.opsForValue().get(cacheKey));

        // Deactivate product as ADMIN
        mvc.perform(delete(PRODUCTS + "/" + productId).header("Authorization", bearer(adminId, "ADMIN")))
                .andExpect(status().isNoContent());

        // Cache should be evicted
        assertNull(redis.opsForValue().get(cacheKey), "Cache should be evicted after product deactivate");

        // Next CASHIER request should not include deactivated product (CASHIER only sees active)
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ===== 8. Category mutation -> cache evicted for ALL active venues =====
    @Test
    void categoryMutationEvictsCacheForAllActiveVenues() throws Exception {
        // Populate cache for both venues
        String cacheKeyA = "menu:" + venueAId;
        String cacheKeyB = "menu:" + venueBId;

        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueBId))
                        .header("Authorization", bearer(cashierBId, "CASHIER")))
                .andExpect(status().isOk());

        assertNotNull(redis.opsForValue().get(cacheKeyA));
        assertNotNull(redis.opsForValue().get(cacheKeyB));

        // Create category as ADMIN (global mutation)
        String newCatName = fixture + "-new-category";
        mvc.perform(post(CATEGORIES).header("Authorization", bearer(adminId, "ADMIN"))
                        .contentType("application/json")
                        .content("{\"name\":\"" + newCatName + "\"}"))
                .andExpect(status().isCreated());

        // Both caches should be evicted
        assertNull(redis.opsForValue().get(cacheKeyA), "Venue A cache should be evicted after global category mutation");
        assertNull(redis.opsForValue().get(cacheKeyB), "Venue B cache should be evicted after global category mutation");
    }

    // ===== 9. ADMIN/MANAGER requests bypass cache =====
    @Test
    void adminManagerRequestsBypassCache() throws Exception {
        String cacheKey = "menu:" + venueAId;
        assertNull(redis.opsForValue().get(cacheKey), "Cache should be empty initially");

        // ADMIN request should not populate cache
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(adminId, "ADMIN")))
                .andExpect(status().isOk());

        assertNull(redis.opsForValue().get(cacheKey), "Cache should NOT be populated by ADMIN request");

        // MANAGER request should not populate cache
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(managerId, "MANAGER")))
                .andExpect(status().isOk());

        assertNull(redis.opsForValue().get(cacheKey), "Cache should NOT be populated by MANAGER request");

        // Only CASHIER populates cache
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());

        assertNotNull(redis.opsForValue().get(cacheKey), "Cache SHOULD be populated by CASHIER request");
    }

    // ===== 10. Cache invalidation timing: AFTER_COMMIT only =====
    @Test
    void cacheInvalidationOnlyAfterCommitNotOnRollback() throws Exception {
        String cacheKey = "menu:" + venueAId;
        String rollbackSku = fixture + "-rollback-sku";

        // Populate cache
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        assertNotNull(redis.opsForValue().get(cacheKey));

        // Call the real service through its Spring proxy inside an outer
        // transaction, let it publish CatalogMutatedEvent, then force a
        // rollback before commit. The AFTER_COMMIT listener must not run.
        String token = bearer(adminId, "ADMIN").replace("Bearer ", "");
        Jwt jwt = jwtDecoder.decode(token);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                productService.createProduct(new ProductService.ProductCreateRequest(
                        venueAId, categoryId, rollbackSku, fixture + "-rollback",
                        100L, false, null));
                throw new IllegalStateException("force rollback after event publication");
            }));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE venue_id = ? AND sku = ?",
                Integer.class, venueAId, rollbackSku),
                "Product insert must be rolled back");
        assertNotNull(redis.opsForValue().get(cacheKey),
                "Cache should NOT be evicted when transaction rolls back");
    }

    // ===== Verify cached payload does not include recipe/inventory fields =====
    @Test
    void cachedPayloadExcludesRecipeAndInventoryFields() throws Exception {
        String cacheKey = "menu:" + venueAId;

        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());

        String cached = redis.opsForValue().get(cacheKey);
        assertNotNull(cached);

        // Product record fields: id, venueId, categoryId, sku, name, price, bottleKeepable, bottleVolumeMl, active, version, updatedAt
        // Should NOT contain: recipe, items, inventory, quantity, etc.
        assertFalse(cached.contains("recipe"), "Cached payload should not contain recipe field");
        assertFalse(cached.contains("inventory"), "Cached payload should not contain inventory field");
        assertFalse(cached.contains("quantity"), "Cached payload should not contain quantity field");
        assertFalse(cached.contains("ingredient"), "Cached payload should not contain ingredient field");
    }

    // ===== Cache eviction failure doesn't break mutation response =====
    @Test
    void cacheEvictionFailureDoesNotBreakMutationResponse() throws Exception {
        String cacheKey = "menu:" + venueAId;
        String sku = fixture + "-evict-test";

        // Populate cache
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        assertNotNull(redis.opsForValue().get(cacheKey));

        // Make the AFTER_COMMIT eviction fail for this mutation only.
        doThrow(new RedisConnectionFailureException("Redis unavailable"))
                .when(redis).delete(cacheKey);
        try {
            mvc.perform(post(PRODUCTS).header("Authorization", bearer(adminId, "ADMIN"))
                            .contentType("application/json")
                            .content(productJson(venueAId, categoryId, sku, fixture + "-evict-test", 100, false, null)))
                    .andExpect(status().isCreated());

            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM products WHERE venue_id = ? AND sku = ?",
                    Integer.class, venueAId, sku),
                    "Product mutation must still be committed to the database");
            assertNotNull(redis.opsForValue().get(cacheKey),
                    "The failed eviction should leave the stale cache entry untouched");
        } finally {
            Mockito.reset(redis);
        }
    }

    // ===== Additional: Category update evicts cache globally =====
    @Test
    void categoryUpdateEvictsCacheGlobally() throws Exception {
        long tempCategoryId = jdbc.queryForObject("""
                INSERT INTO product_categories (name, sort_order) VALUES (?, 1) RETURNING id
                """, Long.class, fixture + "-temp-cat");

        String cacheKeyA = "menu:" + venueAId;
        String cacheKeyB = "menu:" + venueBId;

        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueBId))
                        .header("Authorization", bearer(cashierBId, "CASHIER")))
                .andExpect(status().isOk());

        assertNotNull(redis.opsForValue().get(cacheKeyA));
        assertNotNull(redis.opsForValue().get(cacheKeyB));

        mvc.perform(put(CATEGORIES + "/" + tempCategoryId).header("Authorization", bearer(adminId, "ADMIN"))
                        .contentType("application/json")
                        .content("{\"name\":\"" + fixture + "-updated-category\",\"sortOrder\":5}"))
                .andExpect(status().isOk());

        assertNull(redis.opsForValue().get(cacheKeyA));
        assertNull(redis.opsForValue().get(cacheKeyB));
    }

    // ===== Additional: Category deactivate evicts cache globally =====
    @Test
    void categoryDeactivateEvictsCacheGlobally() throws Exception {
        long tempCategoryId = jdbc.queryForObject("""
                INSERT INTO product_categories (name, sort_order) VALUES (?, 1) RETURNING id
                """, Long.class, fixture + "-temp-cat-2");

        String cacheKeyA = "menu:" + venueAId;
        String cacheKeyB = "menu:" + venueBId;

        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueAId))
                        .header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isOk());
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueBId))
                        .header("Authorization", bearer(cashierBId, "CASHIER")))
                .andExpect(status().isOk());

        assertNotNull(redis.opsForValue().get(cacheKeyA));
        assertNotNull(redis.opsForValue().get(cacheKeyB));

        mvc.perform(delete(CATEGORIES + "/" + tempCategoryId).header("Authorization", bearer(adminId, "ADMIN")))
                .andExpect(status().isNoContent());

        assertNull(redis.opsForValue().get(cacheKeyA));
        assertNull(redis.opsForValue().get(cacheKeyB));
    }

    private long createUser(String email, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, is_active, version, created_at, updated_at)
                VALUES (?, 'test-hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, email, email, role);
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

    private String productJson(long venueId, long categoryId, String sku, String name, long price, boolean bottleKeepable, Integer bottleVolumeMl) {
        return "{\"venueId\":" + venueId + ",\"categoryId\":" + categoryId + ",\"sku\":\"" + sku
                + "\",\"name\":\"" + name + "\",\"price\":" + price
                + ",\"bottleKeepable\":" + bottleKeepable + ",\"bottleVolumeMl\":" + (bottleVolumeMl == null ? "null" : bottleVolumeMl) + "}";
    }
}