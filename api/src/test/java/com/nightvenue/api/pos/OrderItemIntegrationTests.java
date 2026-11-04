package com.nightvenue.api.pos;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.businessday.BusinessDateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, OrderItemIntegrationTests.ClockConfiguration.class})
class OrderItemIntegrationTests {

    private static final String ORDERS = "/api/v1/orders";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired OrderItemTestClock clock;
    @Autowired BusinessDateService businessDates;
    @Autowired ObjectMapper objectMapper;

    private String fixture;
    private long venueId;
    private long barLocationId;
    private long otherVenueId;
    private long otherBarLocationId;
    private long adminId;
    private long managerId;
    private long cashierId;
    private long customerUserId;
    private final Set<UUID> recordedIdempotencyKeys = Collections.synchronizedSet(new HashSet<>());

    @BeforeEach
    void createFixtures() {
        fixture = "O5B-" + UUID.randomUUID().toString().substring(0, 8);
        clock.set(Instant.parse("2026-10-24T12:00:00Z"));

        venueId = createVenue(fixture + "-a", true, 1100, 500, 6);
        barLocationId = createStockLocation(venueId, "Bar", "BAR");

        otherVenueId = createVenue(fixture + "-b", true, 1100, 500, 6);
        otherBarLocationId = createStockLocation(otherVenueId, "Other Bar", "BAR");

        adminId = demoUserId("ADMIN");
        managerId = demoUserId("MANAGER");
        cashierId = demoUserId("CASHIER");
        customerUserId = demoUserId("CUSTOMER");

        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", managerId, venueId);
        jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", cashierId, venueId);
    }

    @AfterEach
    void removeFixtures() {
        if (fixture == null) {
            return;
        }
        jdbc.update("""
                DELETE FROM inventory_transactions
                WHERE inventory_item_id IN (
                    SELECT ii.id FROM inventory_items ii
                    JOIN stock_locations sl ON sl.id = ii.location_id
                    JOIN venues v ON v.id = sl.venue_id
                    WHERE v.code LIKE ?
                )
                """, fixture + "%");
        jdbc.update("""
                DELETE FROM order_items
                WHERE order_id IN (
                    SELECT o.id FROM orders o JOIN venues v ON v.id = o.venue_id
                    WHERE v.code LIKE ?
                )
                """, fixture + "%");
        jdbc.update("""
                DELETE FROM orders
                WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixture + "%");
        jdbc.update("""
                DELETE FROM inventory_items
                WHERE location_id IN (
                    SELECT sl.id FROM stock_locations sl JOIN venues v ON v.id = sl.venue_id
                    WHERE v.code LIKE ?
                )
                """, fixture + "%");
        jdbc.update("""
                DELETE FROM stock_locations
                WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixture + "%");
        jdbc.update("""
                DELETE FROM recipe_items
                WHERE recipe_id IN (
                    SELECT r.id FROM recipes r JOIN products p ON p.id = r.product_id
                    JOIN venues v ON v.id = p.venue_id
                    WHERE v.code LIKE ?
                )
                """, fixture + "%");
        jdbc.update("""
                DELETE FROM recipes
                WHERE product_id IN (SELECT id FROM products WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?))
                """, fixture + "%");
        jdbc.update("""
                DELETE FROM products
                WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)
                """, fixture + "%");
        jdbc.update("DELETE FROM ingredients WHERE sku LIKE ?", fixture + "%");
        jdbc.update("DELETE FROM venue_tables WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)",
                fixture + "%");
        jdbc.update("DELETE FROM user_venues WHERE venue_id IN (SELECT id FROM venues WHERE code LIKE ?)",
                fixture + "%");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", fixture + "%");

        synchronized (recordedIdempotencyKeys) {
            for (UUID key : recordedIdempotencyKeys) {
                jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key);
            }
            recordedIdempotencyKeys.clear();
        }
    }

    // --- RBAC Tests ---

    @Test
    void rbacMatrixForAddItems() throws Exception {
        long ingId = createIngredient(fixture + "-ing1", "Vodka", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("1000.000"));
        long prodId = createProduct(venueId, fixture + "-p1", "Vodka Shot", 50_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("45.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String body = itemsJson(List.of(new ItemReq(prodId, 1)));

        // ADMIN, MANAGER, CASHIER -> 201 Created
        for (String role : List.of("ADMIN", "MANAGER", "CASHIER")) {
            String auth = bearer(userId(role), role);
            UUID key = UUID.randomUUID();
            recordedIdempotencyKeys.add(key);
            mvc.perform(post(ORDERS + "/" + orderId + "/items")
                            .header("Authorization", auth)
                            .header("Idempotency-Key", key.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(orderId))
                    .andExpect(jsonPath("$.items").isArray());
        }

        // CUSTOMER -> 403 Forbidden
        UUID custKey = UUID.randomUUID();
        recordedIdempotencyKeys.add(custKey);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", bearer(customerUserId, "CUSTOMER"))
                        .header("Idempotency-Key", custKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        // Anonymous -> 401 Unauthorized
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        // User without access to venue -> 404 Not Found
        long otherOrderId = insertOrder(otherVenueId, null, "OPEN", 0);
        UUID mgrOtherKey = UUID.randomUUID();
        recordedIdempotencyKeys.add(mgrOtherKey);
        mvc.perform(post(ORDERS + "/" + otherOrderId + "/items")
                        .header("Authorization", bearer(managerId, "MANAGER"))
                        .header("Idempotency-Key", mgrOtherKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Resource not found"));
    }

    // --- Status and Acceptance Tests ---

    @Test
    void nonAcceptingOrderStatusReturns409() throws Exception {
        long ingId = createIngredient(fixture + "-ing2", "Gin", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("500.000"));
        long prodId = createProduct(venueId, fixture + "-p2", "Gin Shot", 60_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("45.000"))));

        String auth = bearer(cashierId, "CASHIER");
        String body = itemsJson(List.of(new ItemReq(prodId, 1)));

        // Accepted statuses: OPEN and PARTIALLY_PAID
        for (String acceptedStatus : List.of("OPEN", "PARTIALLY_PAID")) {
            long oid = insertOrder(venueId, null, acceptedStatus, 0);
            UUID key = UUID.randomUUID();
            recordedIdempotencyKeys.add(key);
            mvc.perform(post(ORDERS + "/" + oid + "/items")
                            .header("Authorization", auth)
                            .header("Idempotency-Key", key.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        // Rejected statuses: PAID, VOIDED, REFUNDED -> 409 Conflict
        for (String rejectedStatus : List.of("PAID", "VOIDED", "REFUNDED")) {
            long oid = insertOrder(venueId, null, rejectedStatus, 0);
            UUID key = UUID.randomUUID();
            recordedIdempotencyKeys.add(key);
            mvc.perform(post(ORDERS + "/" + oid + "/items")
                            .header("Authorization", auth)
                            .header("Idempotency-Key", key.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict());
        }
    }

    // --- Product Validation Tests (422) ---

    @Test
    void productValidationsReturn422() throws Exception {
        long ingId = createIngredient(fixture + "-ing3", "Rum", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("500.000"));
        String auth = bearer(cashierId, "CASHIER");
        long orderId = insertOrder(venueId, null, "OPEN", 0);

        // 1. Inactive product -> 422
        long inactiveProdId = createProduct(venueId, fixture + "-inact", "Inactive Rum", 50_000, false, null, false);
        createRecipe(inactiveProdId, List.of(new RecipeItemDef(ingId, new BigDecimal("30.000"))));
        UUID key1 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key1);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key1.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(inactiveProdId, 1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Product is not active"));

        // 2. Other-venue product -> 422
        long otherProdId = createProduct(otherVenueId, fixture + "-otherp", "Other Rum", 50_000, false, null, true);
        createRecipe(otherProdId, List.of(new RecipeItemDef(ingId, new BigDecimal("30.000"))));
        UUID key2 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key2);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key2.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(otherProdId, 1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Product does not belong to this venue"));

        // 3. Bottle keepable product with quantity != 1 -> 422 (BR-29)
        long bottleProdId = createProduct(venueId, fixture + "-bottle", "Whiskey Bottle", 1_500_000, true, 750, true);
        createRecipe(bottleProdId, List.of(new RecipeItemDef(ingId, new BigDecimal("750.000"))));
        UUID key3 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key3);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key3.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(bottleProdId, 2)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Bottle product quantity must be 1"));

        // 4. Product without recipe -> 422
        long noRecipeProdId = createProduct(venueId, fixture + "-norecipe", "Mystery Drink", 50_000, false, null, true);
        UUID key4 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key4);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key4.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(noRecipeProdId, 1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Product has no recipe"));
    }

    // --- Validation and Limit Tests (400) ---

    @Test
    void headerAndPayloadValidationLimitsReturn400() throws Exception {
        long ingId = createIngredient(fixture + "-ing4", "Tequila", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("500.000"));
        long prodId = createProduct(venueId, fixture + "-p4", "Tequila Shot", 50_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("30.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        String validBody = itemsJson(List.of(new ItemReq(prodId, 1)));

        // Missing Idempotency-Key header -> 400
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isBadRequest());

        // Malformed Idempotency-Key header -> 400
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isBadRequest());

        // Empty items list -> 400
        UUID key1 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key1);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key1.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        // 51 items (> 50 limit) -> 400
        List<ItemReq> fiftyOneItems = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOneItems.add(new ItemReq(prodId, 1));
        }
        UUID key2 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key2);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key2.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(fiftyOneItems)))
                .andExpect(status().isBadRequest());

        // Quantity 0 -> 400
        UUID key3 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key3);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key3.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 0)))))
                .andExpect(status().isBadRequest());

        // Quantity 1000 (> 999 limit) -> 400
        UUID key4 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key4);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key4.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 1000)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void priceQuantityOverflowReturns422() throws Exception {
        long ingId = createIngredient(fixture + "-ing-ovf", "Gold Flakes", "GRAM", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("100.000"));
        // Price near Long.MAX_VALUE
        long expensiveProdId = createProduct(venueId, fixture + "-p-ovf", "Ultra Luxury", Long.MAX_VALUE / 2 + 10, false, null, true);
        createRecipe(expensiveProdId, List.of(new RecipeItemDef(ingId, new BigDecimal("1.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);

        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(expensiveProdId, 2)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Line total overflow"));
    }

    @Test
    void subtotalOverflowReturns422AndRollsBackEverything() throws Exception {
        long ingId = createIngredient(fixture + "-ing-sub-ovf", "Rare Spirit", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("100.000"));
        long prodId = createProduct(venueId, fixture + "-p-sub-ovf", "Priceless Spirit",
                Long.MAX_VALUE / 2, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("0.001"))));

        long orderId = insertOrder(venueId, null, "OPEN", 17);
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);
        String auth = bearer(cashierId, "CASHIER");
        Timestamp updatedAtBefore = jdbc.queryForObject(
                "SELECT updated_at FROM orders WHERE id = ?", Timestamp.class, orderId);
        long versionBefore = jdbc.queryForObject(
                "SELECT version FROM orders WHERE id = ?", Long.class, orderId);

        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(
                                new ItemReq(prodId, 1), new ItemReq(prodId, 1), new ItemReq(prodId, 1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Subtotal overflow"));

        assertEquals(0, countOrderItems(orderId));
        assertEquals(0, countLedgerRowsForOrder(orderId));
        assertEquals(0, countIdempotencyKeys(key));
        assertEquals(new BigDecimal("100.000"), getStock(barLocationId, ingId));
        assertEquals(17L, jdbc.queryForObject("SELECT subtotal FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(versionBefore, jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(updatedAtBefore,
                jdbc.queryForObject("SELECT updated_at FROM orders WHERE id = ?", Timestamp.class, orderId));
    }

    @Test
    void unknownOrderAndProductReturn404And422() throws Exception {
        UUID unknownOrderKey = UUID.randomUUID();
        recordedIdempotencyKeys.add(unknownOrderKey);
        mvc.perform(post(ORDERS + "/999999999/items")
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", unknownOrderKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(1, 1)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Resource not found"));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        UUID unknownProductKey = UUID.randomUUID();
        recordedIdempotencyKeys.add(unknownProductKey);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", unknownProductKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(999999999, 1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Product does not exist"));
    }

    // --- Insufficient Stock & Full Rollback Tests ---

    @Test
    void insufficientStockRollsBackEverything() throws Exception {
        long ingId = createIngredient(fixture + "-ing5", "Whiskey", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("20.000"));
        long prodId = createProduct(venueId, fixture + "-p5", "Whiskey Shot", 50_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("30.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);

        // Request requires 30.000 ML but only 20.000 ML available -> 409 naming the ingredient
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Insufficient stock: Whiskey"));

        // Assert 0 order_items, 0 ledger rows, 0 idempotency rows for key, stock untouched
        assertEquals(0, countOrderItems(orderId));
        assertEquals(0, countLedgerRowsForOrder(orderId));
        assertEquals(0, countIdempotencyKeys(key));
        assertEquals(new BigDecimal("20.000"), getStock(barLocationId, ingId));

        // Restock ingredient
        jdbc.update("UPDATE inventory_items SET quantity = 100.000 WHERE location_id = ? AND ingredient_id = ?",
                barLocationId, ingId);

        // Retry with the exact same key succeeds
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 1)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].quantity").value(1));

        assertEquals(1, countOrderItems(orderId));
        assertEquals(1, countLedgerRowsForOrder(orderId));
        assertEquals(1, countIdempotencyKeys(key));
        assertEquals(new BigDecimal("70.000"), getStock(barLocationId, ingId));
    }

    @Test
    void partialRollbackWithTwoIngredientsRestoresFirstStock() throws Exception {
        // Create first ingredient with lower inventory_items.id and sufficient stock
        long ing1Id = createIngredient(fixture + "-ing6a", "FirstIng", "ML", true);
        long inv1Id = createInventoryItem(barLocationId, ing1Id, new BigDecimal("50.000"));

        // Create second ingredient with higher inventory_items.id and insufficient stock
        long ing2Id = createIngredient(fixture + "-ing6b", "SecondIng", "ML", true);
        long inv2Id = createInventoryItem(barLocationId, ing2Id, new BigDecimal("5.000"));

        assertTrue(inv2Id > inv1Id, "Second ingredient inventory item ID must be higher than first");

        // Product recipe requires 10.000 of ing1 and 10.000 of ing2
        long prodId = createProduct(venueId, fixture + "-p6", "Multi Mix", 80_000, false, null, true);
        createRecipe(prodId, List.of(
                new RecipeItemDef(ing1Id, new BigDecimal("10.000")),
                new RecipeItemDef(ing2Id, new BigDecimal("10.000"))
        ));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);

        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Insufficient stock: SecondIng"));

        // Assert first ingredient stock restored to 50.000, second remains 5.000
        assertEquals(new BigDecimal("50.000"), getStock(barLocationId, ing1Id));
        assertEquals(new BigDecimal("5.000"), getStock(barLocationId, ing2Id));
        assertEquals(0, countOrderItems(orderId));
        assertEquals(0, countLedgerRowsForOrder(orderId));
        assertEquals(0, countIdempotencyKeys(key));
    }

    // --- Idempotency & Replay Tests ---

    @Test
    void replaySameKeyReturnsIdenticalBodyWithoutExtraDeductions() throws Exception {
        long ingId = createIngredient(fixture + "-ing7", "Scotch", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("500.000"));
        long prodId = createProduct(venueId, fixture + "-p7", "Scotch Drink", 75_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("40.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);
        String body = itemsJson(List.of(new ItemReq(prodId, 1)));

        // First call
        MvcResult first = mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String firstResp = first.getResponse().getContentAsString();

        long versionAfterFirst = jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, orderId);
        Timestamp updatedAtAfterFirst = jdbc.queryForObject("SELECT updated_at FROM orders WHERE id = ?", Timestamp.class, orderId);

        // Second call (replay)
        MvcResult second = mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String secondResp = second.getResponse().getContentAsString();

        assertEquals(firstResp, secondResp);
        assertEquals(versionAfterFirst, jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(updatedAtAfterFirst, jdbc.queryForObject("SELECT updated_at FROM orders WHERE id = ?", Timestamp.class, orderId));
        assertEquals(1, countOrderItems(orderId));
        assertEquals(1, countLedgerRowsForOrder(orderId));
        assertEquals(new BigDecimal("460.000"), getStock(barLocationId, ingId));
    }

    @Test
    void sameKeyDifferentPayloadReturns422() throws Exception {
        long ingId = createIngredient(fixture + "-ing8", "Cognac", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("500.000"));
        long prod1Id = createProduct(venueId, fixture + "-p8a", "Cognac A", 70_000, false, null, true);
        long prod2Id = createProduct(venueId, fixture + "-p8b", "Cognac B", 90_000, false, null, true);
        createRecipe(prod1Id, List.of(new RecipeItemDef(ingId, new BigDecimal("30.000"))));
        createRecipe(prod2Id, List.of(new RecipeItemDef(ingId, new BigDecimal("30.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);

        // First payload: prod1 qty 1
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prod1Id, 1)))))
                .andExpect(status().isCreated());

        // Same key, different payload (prod2 qty 1) -> 422
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prod2Id, 1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key reused with a different payload"));

        // Same key on another orderId -> 422
        long anotherOrderId = insertOrder(venueId, null, "OPEN", 0);
        mvc.perform(post(ORDERS + "/" + anotherOrderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prod1Id, 1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key reused with a different payload"));
    }

    @Test
    void sameKeyByDifferentUsersProduceIndependentEffects() throws Exception {
        long ingId = createIngredient(fixture + "-ing9", "Wine", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("500.000"));
        long prodId = createProduct(venueId, fixture + "-p9", "Red Wine", 60_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("150.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        UUID sharedKey = UUID.randomUUID();
        recordedIdempotencyKeys.add(sharedKey);
        String body = itemsJson(List.of(new ItemReq(prodId, 1)));

        // Manager adds item with sharedKey
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", bearer(managerId, "MANAGER"))
                        .header("Idempotency-Key", sharedKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Cashier adds item with same sharedKey -> independent effect, 2nd item added
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", bearer(cashierId, "CASHIER"))
                        .header("Idempotency-Key", sharedKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        assertEquals(2, countOrderItems(orderId));
        assertEquals(2, countLedgerRowsForOrder(orderId));
        assertEquals(2, countIdempotencyKeys(sharedKey));
        assertEquals(new BigDecimal("200.000"), getStock(barLocationId, ingId));
    }

    @Test
    void concurrentDuplicateSameKeyBothSucceedWithSingleDeduction() throws Exception {
        long ingId = createIngredient(fixture + "-ing10", "Brandy", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("500.000"));
        long prodId = createProduct(venueId, fixture + "-p10", "Brandy Shot", 50_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("30.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);
        String body = itemsJson(List.of(new ItemReq(prodId, 1)));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<MvcResult>> tasks = List.of(
                () -> {
                    barrier.await();
                    return mvc.perform(post(ORDERS + "/" + orderId + "/items")
                                    .header("Authorization", auth)
                                    .header("Idempotency-Key", key.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                },
                () -> {
                    barrier.await();
                    return mvc.perform(post(ORDERS + "/" + orderId + "/items")
                                    .header("Authorization", auth)
                                    .header("Idempotency-Key", key.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                }
        );

        List<Future<MvcResult>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        MvcResult res1 = futures.get(0).get();
        MvcResult res2 = futures.get(1).get();

        assertEquals(201, res1.getResponse().getStatus());
        assertEquals(201, res2.getResponse().getStatus());
        assertEquals(res1.getResponse().getContentAsString(), res2.getResponse().getContentAsString());

        assertEquals(1, countOrderItems(orderId));
        assertEquals(1, countLedgerRowsForOrder(orderId));
        assertEquals(1, countIdempotencyKeys(key));
        assertEquals(new BigDecimal("470.000"), getStock(barLocationId, ingId));
    }

    @Test
    void twoThreadsSameOrderDifferentKeysBothSucceed() throws Exception {
        long ingId = createIngredient(fixture + "-ing11", "Mezcal", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("500.000"));
        long prodId = createProduct(venueId, fixture + "-p11", "Mezcal Shot", 50_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("25.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key1 = UUID.randomUUID();
        UUID key2 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key1);
        recordedIdempotencyKeys.add(key2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<MvcResult>> tasks = List.of(
                () -> {
                    barrier.await();
                    return mvc.perform(post(ORDERS + "/" + orderId + "/items")
                                    .header("Authorization", auth)
                                    .header("Idempotency-Key", key1.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(itemsJson(List.of(new ItemReq(prodId, 1)))))
                            .andReturn();
                },
                () -> {
                    barrier.await();
                    return mvc.perform(post(ORDERS + "/" + orderId + "/items")
                                    .header("Authorization", auth)
                                    .header("Idempotency-Key", key2.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(itemsJson(List.of(new ItemReq(prodId, 2)))))
                            .andReturn();
                }
        );

        List<Future<MvcResult>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        assertEquals(201, futures.get(0).get().getResponse().getStatus());
        assertEquals(201, futures.get(1).get().getResponse().getStatus());

        assertEquals(2, countOrderItems(orderId));
        assertEquals(2, countLedgerRowsForOrder(orderId));
        // Totals = 50,000 * 1 + 50,000 * 2 = 150,000 subtotal
        long subtotal = jdbc.queryForObject("SELECT subtotal FROM orders WHERE id = ?", Long.class, orderId);
        assertEquals(150_000L, subtotal);
        assertEquals(new BigDecimal("425.000"), getStock(barLocationId, ingId));
    }

    @Test
    void repeatedProductIdInSingleRequestCreatesSeparateRows() throws Exception {
        long ingId = createIngredient(fixture + "-ing12", "Cider", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("1000.000"));
        long prodId = createProduct(venueId, fixture + "-p12", "Apple Cider", 40_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("330.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);

        // Same productId repeated twice
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 1), new ItemReq(prodId, 2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2));

        assertEquals(2, countOrderItems(orderId));
        assertEquals(2, countLedgerRowsForOrder(orderId));
        // Total deduction = 330 * 1 + 330 * 2 = 990 ML -> balance 10.000
        assertEquals(new BigDecimal("10.000"), getStock(barLocationId, ingId));
    }

    // --- Financial Totals Integration Test (D5 Option A) ---

    @Test
    void totalsIntegrationCheckAgainstVenueBps() throws Exception {
        // Venue has tax_rate_bps = 1100 (11%) and service_charge_bps = 500 (5%)
        long ingId = createIngredient(fixture + "-ing13", "Sake", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("1000.000"));
        long prodId = createProduct(venueId, fixture + "-p13", "Warm Sake", 100_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("100.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");
        UUID key = UUID.randomUUID();
        recordedIdempotencyKeys.add(key);

        // Subtotal = 100,000 * 2 = 200,000
        // discount = 0
        // base = 200,000
        // service_charge = 200,000 * 500 / 10000 = 10,000
        // tax = 200,000 * 1100 / 10000 = 22,000
        // total_due = 200,000 + 10,000 + 22,000 = 232,000
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(200_000))
                .andExpect(jsonPath("$.discountAmount").value(0))
                .andExpect(jsonPath("$.serviceChargeAmount").value(10_000))
                .andExpect(jsonPath("$.taxAmount").value(22_000))
                .andExpect(jsonPath("$.totalDue").value(232_000));
    }

    // --- Business Date Boundary & Ledger Chain Tests ---

    @Test
    void businessDateAtCutoffBoundaryAndLedgerChain() throws Exception {
        long ingId = createIngredient(fixture + "-ing14", "Tonic", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("1000.000"));
        long prodId = createProduct(venueId, fixture + "-p14", "Gin Tonic", 60_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("100.000"))));

        long orderId = insertOrder(venueId, null, "OPEN", 0);
        String auth = bearer(cashierId, "CASHIER");

        // 1. Instant 05:59:59 local (UTC 2026-10-23T22:59:59Z) -> businessDate 2026-10-23
        clock.set(Instant.parse("2026-10-23T22:59:59Z"));
        UUID key1 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key1);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key1.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 1)))))
                .andExpect(status().isCreated());

        LocalDate date1 = jdbc.queryForObject(
                "SELECT business_date FROM inventory_transactions WHERE reference_id IN (SELECT id FROM order_items WHERE order_id = ?) ORDER BY id DESC LIMIT 1",
                LocalDate.class, orderId);
        assertEquals(LocalDate.parse("2026-10-23"), date1);

        // 2. Instant 06:00:00 local (UTC 2026-10-23T23:00:00Z) -> businessDate 2026-10-24
        clock.set(Instant.parse("2026-10-23T23:00:00Z"));
        UUID key2 = UUID.randomUUID();
        recordedIdempotencyKeys.add(key2);
        mvc.perform(post(ORDERS + "/" + orderId + "/items")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", key2.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(List.of(new ItemReq(prodId, 1)))))
                .andExpect(status().isCreated());

        LocalDate date2 = jdbc.queryForObject(
                "SELECT business_date FROM inventory_transactions WHERE reference_id IN (SELECT id FROM order_items WHERE order_id = ?) ORDER BY id DESC LIMIT 1",
                LocalDate.class, orderId);
        assertEquals(LocalDate.parse("2026-10-24"), date2);

        // Check balance_after chain
        List<BigDecimal> balances = jdbc.query(
                "SELECT balance_after FROM inventory_transactions WHERE reference_id IN (SELECT id FROM order_items WHERE order_id = ?) ORDER BY id ASC",
                (rs, row) -> rs.getBigDecimal(1), orderId);
        assertEquals(2, balances.size());
        assertEquals(new BigDecimal("900.000"), balances.get(0));
        assertEquals(new BigDecimal("800.000"), balances.get(1));
    }

    // --- DB Level Negative Stock Check ---

    @Test
    void directJdbcNegativeQuantityViolatesCheckConstraint() {
        long ingId = createIngredient(fixture + "-ing-ck", "CheckIng", "ML", true);
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version, updated_at) VALUES (?, ?, -1, 0, 0, now())",
                        barLocationId, ingId));
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("ck_inventory_qty_nonneg"), cause.getMessage());
    }

    // --- EVIDENCE A ---

    @Test
    void evidenceAConcurrencyContentionOnInventoryRow() throws Exception {
        // One ingredient with quantity exactly 100.000
        long ingId = createIngredient(fixture + "-ing-eva", "EvidenceVodka", "ML", true);
        createInventoryItem(barLocationId, ingId, new BigDecimal("100.000"));

        // Recipe need: exactly 10.000 per unit
        long prodId = createProduct(venueId, fixture + "-p-eva", "Evidence Shot", 50_000, false, null, true);
        createRecipe(prodId, List.of(new RecipeItemDef(ingId, new BigDecimal("10.000"))));

        // 20 SEPARATE walk-in orders (so contention is strictly on inventory row, not order lock)
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            orderIds.add(insertOrder(venueId, null, "OPEN", 0));
        }

        String auth = bearer(cashierId, "CASHIER");
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CyclicBarrier barrier = new CyclicBarrier(20);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            final long oid = orderIds.get(i);
            final UUID key = UUID.randomUUID();
            recordedIdempotencyKeys.add(key);
            tasks.add(() -> {
                barrier.await();
                return mvc.perform(post(ORDERS + "/" + oid + "/items")
                                .header("Authorization", auth)
                                .header("Idempotency-Key", key.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(itemsJson(List.of(new ItemReq(prodId, 1)))))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });
        }

        List<Future<Integer>> results = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }

        long successCount = statuses.stream().filter(s -> s == 201).count();
        long conflictCount = statuses.stream().filter(s -> s == 409).count();

        // Target: floor(100/10) = 10 x 201, 10 x 409
        assertEquals(10, successCount, "Exactly 10 requests must succeed");
        assertEquals(10, conflictCount, "Exactly 10 requests must be rejected with 409");

        // Final quantity: 0.000
        BigDecimal finalQty = getStock(barLocationId, ingId);
        assertEquals(new BigDecimal("0.000"), finalQty);

        // 10 SALE_DEDUCT ledger rows
        Integer ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transactions WHERE type = 'SALE_DEDUCT' AND reference_id IN (SELECT id FROM order_items WHERE product_id = ?)",
                Integer.class, prodId);
        assertEquals(10, ledgerCount);

        // Sum of deltas = -100.000
        BigDecimal sumDeltas = jdbc.queryForObject(
                "SELECT SUM(delta) FROM inventory_transactions WHERE type = 'SALE_DEDUCT' AND reference_id IN (SELECT id FROM order_items WHERE product_id = ?)",
                BigDecimal.class, prodId);
        assertEquals(new BigDecimal("-100.000"), sumDeltas);

        // balance_after = {90, 80, ..., 0} each exactly once
        List<BigDecimal> balances = jdbc.query(
                "SELECT balance_after FROM inventory_transactions WHERE type = 'SALE_DEDUCT' AND reference_id IN (SELECT id FROM order_items WHERE product_id = ?) ORDER BY balance_after DESC",
                (rs, row) -> rs.getBigDecimal(1), prodId);
        List<BigDecimal> expectedBalances = List.of(
                new BigDecimal("90.000"), new BigDecimal("80.000"), new BigDecimal("70.000"),
                new BigDecimal("60.000"), new BigDecimal("50.000"), new BigDecimal("40.000"),
                new BigDecimal("30.000"), new BigDecimal("20.000"), new BigDecimal("10.000"),
                new BigDecimal("0.000")
        );
        assertEquals(expectedBalances, balances);

        // Order_items only for successful orders: total exactly 10
        Integer totalOrderItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE product_id = ?", Integer.class, prodId);
        assertEquals(10, totalOrderItems);

        // No quantity ever < 0
        BigDecimal minBalance = jdbc.queryForObject(
                "SELECT MIN(balance_after) FROM inventory_transactions WHERE reference_id IN (SELECT id FROM order_items WHERE product_id = ?)",
                BigDecimal.class, prodId);
        assertTrue(minBalance.compareTo(BigDecimal.ZERO) >= 0);

        String balanceSummary = balances.stream().map(BigDecimal::toPlainString).collect(java.util.stream.Collectors.joining(","));
        System.out.printf("EVIDENCE_A successes=%d conflicts=%d finalQty=%s ledgerRows=%d sumDelta=%s balances=%s minBalance=%s orderItems=%d%n",
                successCount, conflictCount, finalQty.toPlainString(), ledgerCount, sumDeltas.toPlainString(),
                balanceSummary, minBalance.toPlainString(), totalOrderItems);
    }

    // --- Helper methods ---

    private long createVenue(String code, boolean active, int taxRateBps, int serviceChargeBps, int cutoffHour) {
        return jdbc.queryForObject("""
                INSERT INTO venues (
                    code, name, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, is_active, created_at, updated_at
                )
                VALUES (?, ?, 'Asia/Jakarta', ?, ?, ?, ?, now(), now())
                RETURNING id
                """, Long.class, code, code, cutoffHour, taxRateBps, serviceChargeBps, active);
    }

    private long createStockLocation(long venueId, String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO stock_locations (venue_id, name, type)
                VALUES (?, ?, ?)
                RETURNING id
                """, Long.class, venueId, fixture + "-" + name, type);
    }

    private long createIngredient(String sku, String name, String unit, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO ingredients (sku, name, unit, is_active)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, sku, name, unit, active);
    }

    private long createInventoryItem(long locationId, long ingredientId, BigDecimal quantity) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version, updated_at)
                VALUES (?, ?, ?, 0, 0, now())
                RETURNING id
                """, Long.class, locationId, ingredientId, quantity);
    }

    private long createProduct(long venueId, String sku, String name, long price, boolean bottleKeepable, Integer bottleVolumeMl, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO products (
                    venue_id, sku, name, price, is_bottle_keepable, bottle_volume_ml,
                    is_active, version, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, now(), now())
                RETURNING id
                """, Long.class, venueId, sku, name, price, bottleKeepable, bottleVolumeMl, active);
    }

    private void createRecipe(long productId, List<RecipeItemDef> items) {
        long recipeId = jdbc.queryForObject("""
                INSERT INTO recipes (product_id, version, updated_at)
                VALUES (?, 1, now())
                RETURNING id
                """, Long.class, productId);
        for (RecipeItemDef item : items) {
            jdbc.update("""
                    INSERT INTO recipe_items (recipe_id, ingredient_id, quantity)
                    VALUES (?, ?, ?)
                    """, recipeId, item.ingredientId(), item.quantity());
        }
    }

    private long insertOrder(long venue, Long table, String status, long subtotal) {
        return jdbc.queryForObject("""
                INSERT INTO orders (
                    venue_id, order_number, table_id, cashier_id, business_date, status,
                    subtotal, total_due, opened_at, created_at, updated_at, version
                )
                VALUES (?, ?, ?::bigint, ?, DATE '2026-10-24', ?, ?, 0, now(), now(), now(), 0)
                RETURNING id
                """, Long.class, venue, "NV-" + UUID.randomUUID().toString().substring(0, 15), table, cashierId, status, subtotal);
    }

    private BigDecimal getStock(long locationId, long ingredientId) {
        return jdbc.queryForObject("""
                SELECT quantity FROM inventory_items
                WHERE location_id = ? AND ingredient_id = ?
                """, BigDecimal.class, locationId, ingredientId);
    }

    private int countOrderItems(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class, orderId);
    }

    private int countLedgerRowsForOrder(long orderId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_transactions
                WHERE reference_type = 'ORDER_ITEM' AND reference_id IN (SELECT id FROM order_items WHERE order_id = ?)
                """, Integer.class, orderId);
    }

    private int countIdempotencyKeys(UUID key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key);
    }

    private long demoUserId(String role) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                role.toLowerCase() + "@nightvenue.demo");
    }

    private long userId(String role) {
        return switch (role) {
            case "ADMIN" -> adminId;
            case "MANAGER" -> managerId;
            case "CASHIER" -> cashierId;
            default -> throw new IllegalArgumentException("Unknown order role");
        };
    }

    private String bearer(long userId, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String itemsJson(List<ItemReq> items) throws Exception {
        return objectMapper.writeValueAsString(new AddOrderItemsRequest(
                items.stream().map(i -> new AddOrderItemsRequest.ItemRow(i.productId(), i.quantity())).toList()
        ));
    }

    public record RecipeItemDef(long ingredientId, BigDecimal quantity) {}
    public record ItemReq(long productId, int quantity) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        OrderItemTestClock orderItemTestClock() {
            return new OrderItemTestClock(Instant.parse("2026-10-24T12:00:00Z"));
        }
    }

    static final class OrderItemTestClock extends Clock {
        private final AtomicReference<Instant> instant;

        OrderItemTestClock(Instant instant) {
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
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}

