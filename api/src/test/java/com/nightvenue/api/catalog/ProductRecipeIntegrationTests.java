package com.nightvenue.api.catalog;

import com.nightvenue.api.TestcontainersConfig;
import com.nightvenue.api.seed.DemoDataSeeder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ProductRecipeIntegrationTests {
    private static final String PRODUCTS = "/api/v1/products";
    private static final long MISSING_ID = Long.MAX_VALUE;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired DemoDataSeeder seeder;

    private String fixture;
    private long venueId;
    private long otherVenueId;
    private long categoryId;
    private long mlIngredientId;
    private long gramIngredientId;
    private long pieceIngredientId;
    private String triggerName;
    private String functionName;

    @BeforeEach
    void createFixtures() {
        fixture = "4B-" + UUID.randomUUID().toString().substring(0, 8);
        venueId = createVenue(fixture + "-venue-a");
        otherVenueId = createVenue(fixture + "-venue-b");
        jdbc.update("""
                INSERT INTO user_venues (user_id, venue_id)
                SELECT id, ? FROM users WHERE email IN ('manager@nightvenue.demo', 'cashier@nightvenue.demo')
                ON CONFLICT DO NOTHING
                """, venueId);
        categoryId = jdbc.queryForObject("""
                INSERT INTO product_categories (name, sort_order) VALUES (?, 9) RETURNING id
                """, Long.class, fixture + "-category");
        mlIngredientId = createIngredient(fixture + "-ml", "ML");
        gramIngredientId = createIngredient(fixture + "-gram", "GRAM");
        pieceIngredientId = createIngredient(fixture + "-piece", "PIECE");
    }

    @AfterEach
    void removeFixtures() {
        if (triggerName != null) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON recipe_items");
            triggerName = null;
        }
        if (functionName != null) {
            jdbc.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
            functionName = null;
        }
        jdbc.update("DELETE FROM products WHERE sku LIKE ?", fixture + "-%");
        jdbc.update("DELETE FROM ingredients WHERE sku LIKE ?", fixture + "-%");
        jdbc.update("DELETE FROM product_categories WHERE name = ?", fixture + "-category");
        jdbc.update("DELETE FROM venues WHERE code LIKE ?", fixture + "-%");
    }

    @Test
    @DisplayName("PRD-4B-01 cashier reads active products in the assigned venue")
    void cashierCanListAndGetActiveProductsInAssignedVenue() throws Exception {
        String sku = fixture + "-active";
        long productId = createProduct(venueId, sku, true);

        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueId))
                        .header("Authorization", bearer("CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value(sku));
        mvc.perform(get(PRODUCTS + "/" + productId).header("Authorization", bearer("CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(sku));
    }

    @Test
    @DisplayName("PRD-4B-02 cashier never sees inactive products")
    void cashierCannotReadInactiveProductsEvenWithIncludeInactive() throws Exception {
        createProduct(venueId, fixture + "-active", true);
        long inactiveId = createProduct(venueId, fixture + "-inactive", false);

        var response = mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueId))
                        .param("includeInactive", "true")
                        .header("Authorization", bearer("CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sku").value(fixture + "-active"))
                .andReturn();
        assertFalse(response.getResponse().getContentAsString().contains(fixture + "-inactive"));
        mvc.perform(get(PRODUCTS + "/" + inactiveId).param("includeInactive", "true")
                        .header("Authorization", bearer("CASHIER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PRD-4B-03 cashier cannot mutate products")
    void cashierCannotCreateUpdateOrDeactivateProducts() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        mvc.perform(post(PRODUCTS).header("Authorization", bearer("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(venueId, categoryId, fixture + "-new", 100L, false, null)))
                .andExpect(status().isForbidden());
        mvc.perform(put(PRODUCTS + "/" + productId).header("Authorization", bearer("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productUpdateJson(categoryId, fixture + "-updated", 100L, false, null)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PRODUCTS + "/" + productId).header("Authorization", bearer("CASHIER")))
                .andExpect(status().isForbidden());
        assertTrue(jdbc.queryForObject(
                "SELECT is_active FROM products WHERE id = ?", Boolean.class, productId));
    }

    @Test
    @DisplayName("PRD-4B-04 cashier cannot read or write recipes")
    void cashierCannotReadOrWriteRecipes() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        mvc.perform(get(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("CASHIER")))
                .andExpect(status().isForbidden());
        mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(recipeItem(mlIngredientId, "1.000")))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "PRD-4B-ROLE-MATRIX {0} {1} -> {2}")
    @MethodSource("productRecipeRoleMatrix")
    @DisplayName("PRD-4B-ROLE-MATRIX product and recipe endpoint authorization")
    void productAndRecipeEndpointsEnforceRoleMatrix(
            String actor, String endpoint, int expectedStatus) throws Exception {
        long productId = createProduct(venueId, fixture + "-matrix-product", true);
        createRecipe(productId, mlIngredientId, new BigDecimal("1.000"));
        MockHttpServletRequestBuilder request = switch (endpoint) {
            case "GET_LIST" -> get(PRODUCTS).param("venueId", Long.toString(venueId));
            case "GET_PRODUCT" -> get(PRODUCTS + "/" + productId);
            case "POST_PRODUCT" -> post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
                    .content(productJson(venueId, categoryId, fixture + "-matrix-created",
                            100L, false, null));
            case "PUT_PRODUCT" -> put(PRODUCTS + "/" + productId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(productUpdateJson(categoryId, fixture + "-matrix-updated",
                            100L, false, null));
            case "DELETE_PRODUCT" -> delete(PRODUCTS + "/" + productId);
            case "GET_RECIPE" -> get(PRODUCTS + "/" + productId + "/recipe");
            case "PUT_RECIPE" -> put(PRODUCTS + "/" + productId + "/recipe")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(recipeJson(List.of(recipeItem(gramIngredientId, "2.000"))));
            default -> throw new IllegalArgumentException("Unknown endpoint case");
        };
        if (!actor.equals("ANONYMOUS")) {
            request.header("Authorization", bearer(actor));
        }

        mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    static Stream<Arguments> productRecipeRoleMatrix() {
        List<String> endpoints = List.of(
                "GET_LIST", "GET_PRODUCT", "POST_PRODUCT", "PUT_PRODUCT", "DELETE_PRODUCT",
                "GET_RECIPE", "PUT_RECIPE");
        List<String> actors = List.of("ADMIN", "MANAGER", "CASHIER", "CUSTOMER", "ANONYMOUS");
        return actors.stream().flatMap(actor -> endpoints.stream().map(endpoint -> {
            int expected = switch (actor) {
                case "ADMIN", "MANAGER" -> switch (endpoint) {
                    case "GET_LIST", "GET_PRODUCT", "GET_RECIPE", "PUT_PRODUCT", "PUT_RECIPE" -> 200;
                    case "POST_PRODUCT" -> 201;
                    case "DELETE_PRODUCT" -> 204;
                    default -> throw new IllegalArgumentException("Unknown endpoint case");
                };
                case "CASHIER" -> endpoint.equals("GET_LIST") || endpoint.equals("GET_PRODUCT")
                        ? 200 : 403;
                case "CUSTOMER" -> 403;
                case "ANONYMOUS" -> 401;
                default -> throw new IllegalArgumentException("Unknown actor");
            };
            return Arguments.of(actor, endpoint, expected);
        }));
    }

    @Test
    @DisplayName("PRD-4B-05 customer and anonymous callers cannot read products")
    void customerAndAnonymousCannotReadProducts() throws Exception {
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueId))
                        .header("Authorization", bearer("CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PRD-4B-06 manager cannot access another venue's product")
    void managerCannotListOrGetProductOutsideAssignedVenue() throws Exception {
        long productId = createProduct(otherVenueId, fixture + "-other-venue", true);
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(otherVenueId))
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(get(PRODUCTS + "/" + productId).header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PRD-4B-VENUE-WRITE-SCOPE manager writes remain scoped to assigned venue")
    void managerCannotWriteProductsOrRecipesOutsideAssignedVenue() throws Exception {
        long productId = createProduct(otherVenueId, fixture + "-other-venue", true);
        long recipeId = createRecipe(productId, mlIngredientId, new BigDecimal("4.000"));
        long productVersion = jdbc.queryForObject(
                "SELECT version FROM products WHERE id = ?", Long.class, productId);
        long recipeVersion = jdbc.queryForObject(
                "SELECT version FROM recipes WHERE id = ?", Long.class, recipeId);

        mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(otherVenueId, categoryId, fixture + "-scope-create",
                                100L, false, null)))
                .andExpect(status().isNotFound());
        mvc.perform(put(PRODUCTS + "/" + productId).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productUpdateJson(categoryId, fixture + "-scope-update",
                                200L, false, null)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(PRODUCTS + "/" + productId).header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(get(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(recipeItem(gramIngredientId, "8.000")))))
                .andExpect(status().isNotFound());

        assertEquals(productVersion, jdbc.queryForObject(
                "SELECT version FROM products WHERE id = ?", Long.class, productId));
        assertTrue(jdbc.queryForObject(
                "SELECT is_active FROM products WHERE id = ?", Boolean.class, productId));
        assertEquals(fixture + "-other-venue", jdbc.queryForObject(
                "SELECT sku FROM products WHERE id = ?", String.class, productId));
        assertEquals(recipeVersion, jdbc.queryForObject(
                "SELECT version FROM recipes WHERE id = ?", Long.class, recipeId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_items WHERE recipe_id = ? AND ingredient_id = ?",
                Integer.class, recipeId, mlIngredientId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE venue_id = ? AND sku = ?",
                Integer.class, otherVenueId, fixture + "-scope-create"));
    }

    @Test
    @DisplayName("PRD-4B-07 admin can access active products across venues")
    void adminCanReadProductAcrossActiveVenues() throws Exception {
        long productId = createProduct(otherVenueId, fixture + "-other-venue", true);
        mvc.perform(get(PRODUCTS + "/" + productId).header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueId").value(otherVenueId));
    }

    @Test
    @DisplayName("PRD-4B-08 admin cannot access a fake or inactive venue")
    void adminReceivesNotFoundForFakeOrInactiveVenue() throws Exception {
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(MISSING_ID))
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE venues SET is_active = false WHERE id = ?", venueId);
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueId))
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PRD-4B-09 product create stores exact BIGINT price and venue ownership")
    void productCreatePersistsLongPriceAndVenue() throws Exception {
        long price = 9_007_199_254_740_993L;
        var result = mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(venueId, categoryId, fixture + "-created", price, false, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(price))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long id = responseId(result.getResponse().getContentAsString());
        assertEquals(venueId, jdbc.queryForObject("SELECT venue_id FROM products WHERE id = ?", Long.class, id));
        assertEquals(price, jdbc.queryForObject("SELECT price FROM products WHERE id = ?", Long.class, id));
    }

    @Test
    @DisplayName("PRD-4B-10 product update increments version without changing venue")
    void productUpdatePreservesVenueAndIncrementsVersion() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        mvc.perform(put(PRODUCTS + "/" + productId).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productUpdateJson(categoryId, fixture + "-updated", 150L, false, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sku").value(fixture + "-updated"))
                .andExpect(jsonPath("$.venueId").value(venueId));
        assertEquals(venueId, jdbc.queryForObject("SELECT venue_id FROM products WHERE id = ?", Long.class, productId));
    }

    @Test
    @DisplayName("PRD-4B-11 PRD-4B-DELETE-IDEMPOTENT product deactivation is idempotent and preserves its recipe")
    void productDeactivationIsSoftAndKeepsRecipeData() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        long recipeId = createRecipe(productId, mlIngredientId, new BigDecimal("25.000"));

        mvc.perform(delete(PRODUCTS + "/" + productId).header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNoContent());
        mvc.perform(delete(PRODUCTS + "/" + productId).header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNoContent());
        mvc.perform(get(PRODUCTS).param("venueId", Long.toString(venueId))
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + productId + ")]").doesNotExist());
        mvc.perform(get(PRODUCTS + "/" + productId).param("includeInactive", "true")
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_items WHERE recipe_id = ?", Integer.class, recipeId));
        assertFalse(jdbc.queryForObject(
                "SELECT is_active FROM products WHERE id = ?", Boolean.class, productId));
    }

    @Test
    @DisplayName("PRD-4B-12 duplicate SKU returns sanitized catalog conflict")
    void duplicateSkuReturnsExactConflictWithoutSensitiveDetails() throws Exception {
        String sku = fixture + "-duplicate";
        createProduct(venueId, sku, true);
        var response = mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(venueId, categoryId, sku, 100L, false, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "A catalog entry with that identifier already exists"))
                .andReturn();
        String body = response.getResponse().getContentAsString();
        assertFalse(body.contains(sku));
        assertFalse(body.contains("uq_products_venue_sku"));
        assertFalse(body.contains("23505"));
        assertFalse(body.contains("INSERT"));
    }

    @Test
    @DisplayName("PRD-4B-13 malformed product requests return 400")
    void malformedProductRequestsAreRejectedAsBadRequest() throws Exception {
        mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(venueId, categoryId, fixture + "-negative", -1L, false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
        Map<String, Object> missingField = new LinkedHashMap<>();
        missingField.put("venueId", venueId);
        missingField.put("categoryId", categoryId);
        missingField.put("sku", fixture + "-missing");
        missingField.put("price", 1);
        missingField.put("bottleKeepable", false);
        mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingField)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PRD-4B-14 keepable bottle requires volume and database CHECK is enforced")
    void bottleKeepableRequiresVolumeAndDatabaseRejectsInvalidRow() throws Exception {
        mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(venueId, categoryId, fixture + "-no-volume", 100L, true, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "Bottle keepable products require a bottle volume"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO products
                    (venue_id, category_id, sku, name, price, is_bottle_keepable,
                     bottle_volume_ml, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, 'Invalid bottle', 100, true, NULL, true, 0, now(), now())
                """, venueId, categoryId, fixture + "-db-invalid"));
        mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(venueId, categoryId, fixture + "-zero-volume", 100L, true, 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PRD-4B-15 missing and inactive product categories are handled")
    void productCategoryReferencesReturnNotFoundOrBusinessViolation() throws Exception {
        mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(venueId, MISSING_ID, fixture + "-missing-category", 100L, false, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Category does not exist"));
        jdbc.update("UPDATE product_categories SET is_active = false WHERE id = ?", categoryId);
        mvc.perform(post(PRODUCTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(venueId, categoryId, fixture + "-inactive-category", 100L, false, null)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("PRD-4B-CATEGORY-PUT-VALIDATION missing and inactive update categories return 422")
    void productUpdateRejectsMissingAndInactiveCategories() throws Exception {
        long productId = createProduct(venueId, fixture + "-category-update", true);
        mvc.perform(put(PRODUCTS + "/" + productId).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productUpdateJson(MISSING_ID, fixture + "-missing-category-update",
                                100L, false, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Category does not exist"));

        jdbc.update("UPDATE product_categories SET is_active = false WHERE id = ?", categoryId);
        mvc.perform(put(PRODUCTS + "/" + productId).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productUpdateJson(categoryId, fixture + "-inactive-category-update",
                                100L, false, null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Cannot assign an inactive category"));
        assertEquals(fixture + "-category-update", jdbc.queryForObject(
                "SELECT sku FROM products WHERE id = ?", String.class, productId));
    }

    @Test
    @DisplayName("PRD-4B-16 missing product IDs return 404")
    void missingProductIdsReturnNotFound() throws Exception {
        mvc.perform(get(PRODUCTS + "/" + MISSING_ID).header("Authorization", bearer("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(put(PRODUCTS + "/" + MISSING_ID).header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productUpdateJson(categoryId, fixture + "-missing", 100L, false, null)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(PRODUCTS + "/" + MISSING_ID).header("Authorization", bearer("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PRD-4B-17 recipe lookup requires an existing product recipe")
    void missingRecipeLookupReturnsNotFound() throws Exception {
        long productId = createProduct(venueId, fixture + "-no-recipe", true);
        mvc.perform(get(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(get(PRODUCTS + "/" + MISSING_ID + "/recipe")
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PRD-4B-INACTIVE-PRODUCT-RECIPE managers retain recipe access after product deactivation")
    void managersCanReadRecipeForInactiveProductsButCashiersCannot() throws Exception {
        long productId = createProduct(venueId, fixture + "-inactive-product", false);
        createRecipe(productId, mlIngredientId, new BigDecimal("4.000"));

        mvc.perform(get(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(PRODUCTS + "/" + productId)
                        .header("Authorization", bearer("CASHIER")))
                .andExpect(status().isNotFound());
        mvc.perform(get(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("CASHIER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PRD-4B-18 PUT creates a recipe and GET returns its ingredients")
    void putRecipeCreatesRecipeAndGetReturnsIngredients() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(recipeItem(mlIngredientId, "30.000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items[0].ingredientId").value(mlIngredientId))
                .andExpect(jsonPath("$.items[0].unit").value("ML"));
        mvc.perform(get(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("PRD-4B-19 PUT replaces all recipe items and increments version")
    void putRecipeReplacesAllItemsAndIncrementsVersion() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        long recipeId = createRecipe(productId, mlIngredientId, new BigDecimal("5.000"));
        jdbc.update("""
                INSERT INTO recipe_items (recipe_id, ingredient_id, quantity) VALUES (?, ?, 2)
                """, recipeId, gramIngredientId);
        long oldVersion = jdbc.queryForObject("SELECT version FROM recipes WHERE id = ?", Long.class, recipeId);

        mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(
                                recipeItem(gramIngredientId, "7.125"),
                                recipeItem(pieceIngredientId, "1.000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(oldVersion + 1))
                .andExpect(jsonPath("$.items.length()").value(2));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM recipe_items WHERE recipe_id = ? AND ingredient_id = ?
                """, Integer.class, recipeId, mlIngredientId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_items WHERE recipe_id = ?", Integer.class, recipeId));
    }

    @Test
    @DisplayName("PRD-4B-20 empty recipe is rejected without changing prior recipe")
    void emptyRecipeReplacementReturns422AndPreservesCurrentRecipe() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        long recipeId = createRecipe(productId, mlIngredientId, new BigDecimal("9.000"));
        long version = jdbc.queryForObject("SELECT version FROM recipes WHERE id = ?", Long.class, recipeId);
        mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isUnprocessableContent());
        assertEquals(version, jdbc.queryForObject("SELECT version FROM recipes WHERE id = ?", Long.class, recipeId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_items WHERE recipe_id = ? AND ingredient_id = ?",
                Integer.class, recipeId, mlIngredientId));
    }

    @Test
    @DisplayName("PRD-4B-QUANTITY-VALIDATION recipe quantities require positive three-decimal values")
    void recipeQuantityRejectsZeroNegativeAndExcessScale() throws Exception {
        long productId = createProduct(venueId, fixture + "-quantity-validation", true);
        for (String invalidQuantity : List.of("0.000", "-0.001", "0.1234")) {
            mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                            .header("Authorization", bearer("MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(recipeJson(List.of(recipeItem(
                                    mlIngredientId, invalidQuantity)))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Request validation failed"));
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipes WHERE product_id = ?", Integer.class, productId));
    }

    @Test
    @DisplayName("PRD-4B-21 duplicate recipe ingredients return 422")
    void duplicateRecipeIngredientsReturnBusinessViolation() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(
                                recipeItem(mlIngredientId, "1.000"),
                                recipeItem(mlIngredientId, "2.000")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "A recipe cannot contain the same ingredient more than once"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recipes WHERE product_id = ?",
                Integer.class, productId));
    }

    @Test
    @DisplayName("PRD-4B-22 missing and inactive recipe ingredients return 422")
    void missingOrInactiveRecipeIngredientsReturnBusinessViolation() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        long recipeId = createRecipe(productId, mlIngredientId, new BigDecimal("4.000"));

        mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(recipeItem(MISSING_ID, "1.000")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "A recipe references an ingredient that does not exist"));
        jdbc.update("UPDATE ingredients SET is_active = false WHERE id = ?", gramIngredientId);
        mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(recipeItem(gramIngredientId, "1.000")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Cannot use an inactive ingredient"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_items WHERE recipe_id = ? AND ingredient_id = ?",
                Integer.class, recipeId, mlIngredientId));
    }

    @Test
    @DisplayName("PRD-4B-23 recipe replacement serializes with ingredient deactivation")
    void recipeReplacementLocksIngredientsAgainstConcurrentDeactivation() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        installSleepTrigger();
        CompletableFuture<Integer> recipeRequest = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                                .header("Authorization", bearer("MANAGER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(recipeJson(List.of(recipeItem(mlIngredientId, "3.000")))))
                        .andReturn().getResponse().getStatus();
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });

        assertTrue(waitForRecipeItemInsert(), "recipe insert should reach the blocking database trigger");
        mvc.perform(delete("/api/v1/ingredients/" + mlIngredientId)
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isUnprocessableContent());
        assertEquals(200, recipeRequest.get(8, TimeUnit.SECONDS));
        assertTrue(jdbc.queryForObject(
                "SELECT is_active FROM ingredients WHERE id = ?", Boolean.class, mlIngredientId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM recipe_items ri
                JOIN recipes r ON r.id = ri.recipe_id
                WHERE r.product_id = ? AND ri.ingredient_id = ?
                """, Integer.class, productId, mlIngredientId));
    }

    @Test
    @DisplayName("PRD-4B-24 failed recipe item insert rolls back replacement")
    void failedRecipeItemInsertRollsBackRecipeAndItems() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        long recipeId = createRecipe(productId, mlIngredientId, new BigDecimal("6.000"));
        long version = jdbc.queryForObject("SELECT version FROM recipes WHERE id = ?", Long.class, recipeId);
        installForeignKeyFailureTrigger(pieceIngredientId);

        var response = mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(
                                recipeItem(gramIngredientId, "7.000"),
                                recipeItem(pieceIngredientId, "2.000")))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andReturn();
        String body = response.getResponse().getContentAsString();
        assertFalse(body.contains("recipe_items"));
        assertFalse(body.contains(Long.toString(MISSING_ID)));
        assertEquals(version, jdbc.queryForObject("SELECT version FROM recipes WHERE id = ?", Long.class, recipeId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_items WHERE recipe_id = ? AND ingredient_id = ?",
                Integer.class, recipeId, mlIngredientId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recipe_items WHERE recipe_id = ?", Integer.class, recipeId));
    }

    @Test
    @DisplayName("PRD-4B-25 recipe decimal quantities round-trip exactly")
    void recipeQuantityRoundTripsAsExactBigDecimal() throws Exception {
        long productId = createProduct(venueId, fixture + "-product", true);
        BigDecimal quantity = new BigDecimal("0.125");
        var result = mvc.perform(put(PRODUCTS + "/" + productId + "/recipe")
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recipeJson(List.of(recipeItem(mlIngredientId, quantity.toPlainString())))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(quantity, response.path("items").get(0).path("quantity").decimalValue());
        assertEquals(quantity, jdbc.queryForObject("""
                SELECT ri.quantity FROM recipe_items ri
                JOIN recipes r ON r.id = ri.recipe_id WHERE r.product_id = ?
                """, BigDecimal.class, productId));
    }

    @Test
    @DisplayName("PRD-4B-26 PRD-4B-BOTTLE-BAR-STOCK bottle recipes use stocked PIECE ingredients")
    void seededBottleRecipesUsePhysicalPiecesAndCocktailPoursStayLiquid() throws Exception {
        List<String[]> bottles = List.of(
                new String[]{"bottle-vodka-700", "vodka"},
                new String[]{"bottle-gin-700", "gin"},
                new String[]{"bottle-rum-700", "white-rum"},
                new String[]{"bottle-tequila-700", "tequila"},
                new String[]{"bottle-whiskey-700", "whiskey"});
        for (String[] bottle : bottles) {
            List<ProductRecipeRow> rows = jdbc.query("""
                    SELECT i.sku, i.unit, ri.quantity
                    FROM products p
                    JOIN recipes r ON r.product_id = p.id
                    JOIN recipe_items ri ON ri.recipe_id = r.id
                    JOIN ingredients i ON i.id = ri.ingredient_id
                    WHERE p.sku = ?
                    """, (rs, rowNum) -> new ProductRecipeRow(
                    rs.getString("sku"), rs.getString("unit"), rs.getBigDecimal("quantity")),
                    bottle[0]);
            assertFalse(rows.isEmpty());
            for (ProductRecipeRow row : rows) {
                assertEquals(bottle[0], row.ingredientSku());
                assertEquals("PIECE", row.unit());
                assertEquals(new BigDecimal("1.000"), row.quantity());
            }
            assertEquals("ML", jdbc.queryForObject(
                    "SELECT unit FROM ingredients WHERE sku = ?", String.class, bottle[1]));
            List<BottleInventoryRow> stockRows = jdbc.query("""
                    SELECT v.code, i.sku, ii.quantity,
                           COALESCE((
                               SELECT SUM(it.delta) FROM inventory_transactions it
                               WHERE it.inventory_item_id = ii.id
                           ), 0) AS total_delta,
                           (
                               SELECT it.balance_after FROM inventory_transactions it
                               WHERE it.inventory_item_id = ii.id
                               ORDER BY it.created_at DESC, it.id DESC LIMIT 1
                           ) AS last_balance,
                           (
                               SELECT COUNT(*) FROM inventory_transactions it
                               WHERE it.inventory_item_id = ii.id
                           ) AS ledger_count
                    FROM inventory_items ii
                    JOIN ingredients i ON i.id = ii.ingredient_id
                    JOIN stock_locations sl ON sl.id = ii.location_id
                    JOIN venues v ON v.id = sl.venue_id
                    WHERE i.sku = ? AND sl.type = 'BAR'
                      AND v.code IN ('NV-JKT-01', 'NV-JKT-02')
                    ORDER BY v.code
                    """, (rs, rowNum) -> new BottleInventoryRow(
                    rs.getString("code"), rs.getString("sku"), rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("total_delta"), rs.getBigDecimal("last_balance"),
                    rs.getLong("ledger_count")), bottle[0]);
            assertEquals(2, stockRows.size());
            for (BottleInventoryRow stock : stockRows) {
                assertTrue(stock.quantity().signum() > 0);
                assertEquals(stock.quantity(), stock.totalDelta());
                assertEquals(stock.quantity(), stock.lastBalance());
                assertTrue(stock.ledgerCount() > 0);
            }
        }
        List<ProductRecipeRow> cocktailPours = jdbc.query("""
                SELECT i.sku, i.unit, ri.quantity
                FROM products p
                JOIN recipes r ON r.product_id = p.id
                JOIN recipe_items ri ON ri.recipe_id = r.id
                JOIN ingredients i ON i.id = ri.ingredient_id
                WHERE p.sku = 'cocktail-citrus-highball' AND i.sku = 'vodka'
                """, (rs, rowNum) -> new ProductRecipeRow(
                rs.getString("sku"), rs.getString("unit"), rs.getBigDecimal("quantity")));
        assertFalse(cocktailPours.isEmpty());
        for (ProductRecipeRow cocktailPour : cocktailPours) {
            assertEquals("ML", cocktailPour.unit());
            assertEquals(new BigDecimal("45.000"), cocktailPour.quantity());
        }

        List<BottleInventoryRow> beforeReseed = jdbc.query("""
                SELECT v.code, i.sku, ii.quantity,
                       COALESCE((
                           SELECT SUM(it.delta) FROM inventory_transactions it
                           WHERE it.inventory_item_id = ii.id
                       ), 0) AS total_delta,
                       (
                           SELECT it.balance_after FROM inventory_transactions it
                           WHERE it.inventory_item_id = ii.id
                           ORDER BY it.created_at DESC, it.id DESC LIMIT 1
                       ) AS last_balance,
                       (
                           SELECT COUNT(*) FROM inventory_transactions it
                           WHERE it.inventory_item_id = ii.id
                       ) AS ledger_count
                FROM inventory_items ii
                JOIN ingredients i ON i.id = ii.ingredient_id
                JOIN stock_locations sl ON sl.id = ii.location_id
                JOIN venues v ON v.id = sl.venue_id
                WHERE i.sku LIKE 'bottle-%' AND sl.type = 'BAR'
                  AND v.code IN ('NV-JKT-01', 'NV-JKT-02')
                ORDER BY v.code, i.sku
                """, ProductRecipeIntegrationTests::mapBottleInventoryRow);
        seeder.run(new DefaultApplicationArguments(new String[0]));
        List<BottleInventoryRow> afterReseed = jdbc.query("""
                SELECT v.code, i.sku, ii.quantity,
                       COALESCE((
                           SELECT SUM(it.delta) FROM inventory_transactions it
                           WHERE it.inventory_item_id = ii.id
                       ), 0) AS total_delta,
                       (
                           SELECT it.balance_after FROM inventory_transactions it
                           WHERE it.inventory_item_id = ii.id
                           ORDER BY it.created_at DESC, it.id DESC LIMIT 1
                       ) AS last_balance,
                       (
                           SELECT COUNT(*) FROM inventory_transactions it
                           WHERE it.inventory_item_id = ii.id
                       ) AS ledger_count
                FROM inventory_items ii
                JOIN ingredients i ON i.id = ii.ingredient_id
                JOIN stock_locations sl ON sl.id = ii.location_id
                JOIN venues v ON v.id = sl.venue_id
                WHERE i.sku LIKE 'bottle-%' AND sl.type = 'BAR'
                  AND v.code IN ('NV-JKT-01', 'NV-JKT-02')
                ORDER BY v.code, i.sku
                """, ProductRecipeIntegrationTests::mapBottleInventoryRow);
        assertEquals(10, beforeReseed.size());
        assertEquals(beforeReseed, afterReseed);
    }

    private static BottleInventoryRow mapBottleInventoryRow(
            java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BottleInventoryRow(
                rs.getString("code"), rs.getString("sku"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("total_delta"), rs.getBigDecimal("last_balance"),
                rs.getLong("ledger_count"));
    }

    private long createVenue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, created_at, updated_at)
                VALUES (?, ?, now(), now()) RETURNING id
                """, Long.class, code, code);
    }

    private long createIngredient(String sku, String unit) {
        return jdbc.queryForObject("""
                INSERT INTO ingredients (sku, name, unit, is_active)
                VALUES (?, ?, ?, true) RETURNING id
                """, Long.class, sku, sku, unit);
    }

    private long createProduct(long productVenueId, String sku, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO products
                    (venue_id, category_id, sku, name, price, is_bottle_keepable,
                     bottle_volume_ml, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 100, false, NULL, ?, 0, now(), now())
                RETURNING id
                """, Long.class, productVenueId, categoryId, sku, sku, active);
    }

    private long createRecipe(long productId, long ingredientId, BigDecimal quantity) {
        long recipeId = jdbc.queryForObject("""
                INSERT INTO recipes (product_id, version, updated_at)
                VALUES (?, 1, now()) RETURNING id
                """, Long.class, productId);
        jdbc.update("""
                INSERT INTO recipe_items (recipe_id, ingredient_id, quantity) VALUES (?, ?, ?)
                """, recipeId, ingredientId, quantity);
        return recipeId;
    }

    private String productJson(
            Long productVenueId, Long productCategoryId, String sku, Long price,
            Boolean bottleKeepable, Integer bottleVolumeMl) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (productVenueId != null) body.put("venueId", productVenueId);
        if (productCategoryId != null) body.put("categoryId", productCategoryId);
        body.put("sku", sku);
        body.put("name", fixture + "-name");
        body.put("price", price);
        body.put("bottleKeepable", bottleKeepable);
        if (bottleVolumeMl != null) body.put("bottleVolumeMl", bottleVolumeMl);
        else body.put("bottleVolumeMl", null);
        return objectMapper.writeValueAsString(body);
    }

    private String productUpdateJson(
            Long productCategoryId, String sku, Long price,
            Boolean bottleKeepable, Integer bottleVolumeMl) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", productCategoryId);
        body.put("sku", sku);
        body.put("name", fixture + "-updated-name");
        body.put("price", price);
        body.put("bottleKeepable", bottleKeepable);
        body.put("bottleVolumeMl", bottleVolumeMl);
        return objectMapper.writeValueAsString(body);
    }

    private String recipeJson(List<Map<String, Object>> items) throws Exception {
        return objectMapper.writeValueAsString(Map.of("items", items));
    }

    private static Map<String, Object> recipeItem(long ingredientId, String quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ingredientId", ingredientId);
        item.put("quantity", new BigDecimal(quantity));
        return item;
    }

    private String bearer(String role) {
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

    private long responseId(String json) throws Exception {
        return objectMapper.readTree(json).path("id").asLong();
    }

    private void installSleepTrigger() {
        String suffix = fixture.toLowerCase().replace('-', '_');
        functionName = "wait_4b_" + suffix;
        triggerName = "wait_4b_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_sleep(1);
                    RETURN NEW;
                END
                $$
                """.formatted(functionName));
        jdbc.execute("""
                CREATE TRIGGER %s BEFORE INSERT ON recipe_items
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName));
    }

    private boolean waitForRecipeItemInsert() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean active = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE datname = current_database()
                          AND pid <> pg_backend_pid()
                          AND state = 'active'
                          AND query ILIKE '%INSERT INTO recipe_items%'
                    )
                    """, Boolean.class);
            if (active) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private void installForeignKeyFailureTrigger(long ingredientId) {
        String suffix = fixture.toLowerCase().replace('-', '_');
        functionName = "fail_4b_" + suffix;
        triggerName = "fail_4b_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.ingredient_id = %d THEN
                        NEW.ingredient_id := %d;
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(functionName, ingredientId, MISSING_ID));
        jdbc.execute("""
                CREATE TRIGGER %s BEFORE INSERT ON recipe_items
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName));
    }

    private record ProductRecipeRow(String ingredientSku, String unit, BigDecimal quantity) {
    }

    private record BottleInventoryRow(
            String venueCode, String ingredientSku, BigDecimal quantity, BigDecimal totalDelta,
            BigDecimal lastBalance, long ledgerCount) {
    }
}
