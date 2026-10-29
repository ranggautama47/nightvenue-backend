package com.nightvenue.api.catalog;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CatalogIntegrationTests {
    private static final String CATEGORIES = "/api/v1/categories";
    private static final String INGREDIENTS = "/api/v1/ingredients";
    private static final String FIXTURE_PREFIX = "N4A-";
    private static final long MISSING_ID = Long.MAX_VALUE;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;

    private String fixture;
    private long categoryId;
    private long ingredientId;

    @BeforeEach
    void createFixtures() {
        cleanupFixtures();
        fixture = FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 12);
        categoryId = jdbc.queryForObject("""
                INSERT INTO product_categories (name, sort_order)
                VALUES (?, 4)
                RETURNING id
                """, Long.class, fixture + "-category");
        ingredientId = createIngredient(fixture + "-sku", fixture + "-ingredient", "ML");
    }

    @AfterEach
    void cleanup() {
        cleanupFixtures();
    }

    @ParameterizedTest(name = "{0}, role={1}: {2}")
    @MethodSource("rbacMatrix")
    void allCatalogEndpointsEnforceJwtRoleMatrix(String endpoint, String role, int expectedStatus)
            throws Exception {
        MockHttpServletRequestBuilder request = switch (endpoint) {
            case "GET categories" -> get(CATEGORIES);
            case "POST categories" -> post(CATEGORIES).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + fixture + "-new-category\"}");
            case "PUT categories" -> put(CATEGORIES + "/" + categoryId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + fixture + "-updated-category\",\"sortOrder\":8}");
            case "DELETE categories" -> delete(CATEGORIES + "/" + categoryId);
            case "GET ingredients" -> get(INGREDIENTS);
            case "POST ingredients" -> post(INGREDIENTS).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sku\":\"" + fixture + "-new-sku\",\"name\":\"" + fixture
                            + "-new-ingredient\",\"unit\":\"ml\"}");
            case "PUT ingredients" -> put(INGREDIENTS + "/" + ingredientId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sku\":\"" + fixture + "-updated-sku\",\"name\":\"" + fixture
                            + "-updated-ingredient\",\"unit\":\"ML\"}");
            case "DELETE ingredients" -> delete(INGREDIENTS + "/" + ingredientId);
            default -> throw new IllegalArgumentException("Unknown endpoint case");
        };
        if (!role.equals("ANONYMOUS")) {
            request.header("Authorization", bearer(role));
        }
        mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    static Stream<Arguments> rbacMatrix() {
        List<String> endpoints = List.of(
                "GET categories", "POST categories", "PUT categories", "DELETE categories",
                "GET ingredients", "POST ingredients", "PUT ingredients", "DELETE ingredients");
        List<String> roles = java.util.Arrays.asList("ADMIN", "MANAGER", "CASHIER", "CUSTOMER", null);
        return endpoints.stream().flatMap(endpoint -> roles.stream().map(role -> Arguments.of(
                endpoint, role == null ? "ANONYMOUS" : role,
                role == null ? 401 : (role.equals("ADMIN") || role.equals("MANAGER")
                        ? successStatus(endpoint) : 403))));
    }

    @Test
    void categorySortOrderDefaultsOnCreateAndIsPreservedWhenOmittedOnUpdate() throws Exception {
        String manager = bearer("MANAGER");
        String name = fixture + "-sort";
        var created = mvc.perform(post(CATEGORIES).header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" " + name + " \",\"sortOrder\":9}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long createdId = readId(created.getResponse().getContentAsString());
        mvc.perform(put(CATEGORIES + "/" + createdId).header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "-updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").value(9));
        mvc.perform(post(CATEGORIES).header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + fixture + "-default-sort\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(0));
    }

    @Test
    void categoryDeactivationChecksProductsIsIdempotentAndFiltersReads() throws Exception {
        String manager = bearer("MANAGER");
        mvc.perform(delete(CATEGORIES + "/" + categoryId).header("Authorization", manager))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertEquals(0, result.getResponse().getContentLength()));
        mvc.perform(delete(CATEGORIES + "/" + categoryId).header("Authorization", manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(CATEGORIES).header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + categoryId + ")]").doesNotExist());
        for (String role : List.of("ADMIN", "MANAGER")) {
            mvc.perform(get(CATEGORIES).param("includeInactive", "true")
                            .header("Authorization", bearer(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == " + categoryId + ")].active").value(false));
        }
        mvc.perform(post(CATEGORIES).header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + fixture + "-category\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void categoryWithActiveProductsCannotBeDeactivated() throws Exception {
        createProduct(categoryId, fixture + "-product");
        mvc.perform(delete(CATEGORIES + "/" + categoryId).header("Authorization", bearer("ADMIN")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Cannot deactivate category with active products"));
        assertEquals(true, jdbc.queryForObject(
                "SELECT is_active FROM product_categories WHERE id = ?", Boolean.class, categoryId));
    }

    @Test
    void ingredientUnitCannotChangeWhileReferencedByRecipeOrInventory() throws Exception {
        long productId = createProduct(categoryId, fixture + "-recipe-product");
        long recipeId = jdbc.queryForObject("""
                INSERT INTO recipes (product_id, version, updated_at) VALUES (?, 0, now())
                RETURNING id
                """, Long.class, productId);
        jdbc.update("INSERT INTO recipe_items (recipe_id, ingredient_id, quantity) VALUES (?, ?, 1)",
                recipeId, ingredientId);
        mvc.perform(put(INGREDIENTS + "/" + ingredientId).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingredientJson(fixture + "-sku", fixture + "-ingredient", "GRAM")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Cannot change unit of referenced ingredient"));

        long locationId = jdbc.queryForObject(
                "SELECT id FROM stock_locations WHERE type='WAREHOUSE' ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version)
                VALUES (?, ?, 0, 0, 0)
                """, locationId, ingredientId);
        long unusedIngredientId = createIngredient(
                fixture + "-inventory-sku", fixture + "-inventory-ingredient", "ML");
        jdbc.update("""
                INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version)
                VALUES (?, ?, 0, 0, 0)
                """, locationId, unusedIngredientId);
        mvc.perform(put(INGREDIENTS + "/" + unusedIngredientId)
                        .header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingredientJson(fixture + "-inventory-sku",
                                fixture + "-inventory-ingredient", "GRAM")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void ingredientUnitCanChangeWhenUnreferencedAndSameUnitRemainsAllowed() throws Exception {
        mvc.perform(put(INGREDIENTS + "/" + ingredientId).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingredientJson(fixture + "-sku", fixture + "-ingredient", " piece ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("PIECE"));

        long productId = createProduct(categoryId, fixture + "-same-unit-product");
        long recipeId = jdbc.queryForObject("""
                INSERT INTO recipes (product_id, version, updated_at) VALUES (?, 0, now())
                RETURNING id
                """, Long.class, productId);
        jdbc.update("INSERT INTO recipe_items (recipe_id, ingredient_id, quantity) VALUES (?, ?, 1)",
                recipeId, ingredientId);
        mvc.perform(put(INGREDIENTS + "/" + ingredientId).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingredientJson(fixture + "-sku", fixture + "-renamed", "piece")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(fixture + "-renamed"))
                .andExpect(jsonPath("$.unit").value("PIECE"));
    }

    @Test
    void ingredientDeactivationIsIdempotentAndBlockedByActiveRecipe() throws Exception {
        long productId = createProduct(categoryId, fixture + "-active-product");
        long recipeId = jdbc.queryForObject("""
                INSERT INTO recipes (product_id, version, updated_at) VALUES (?, 0, now())
                RETURNING id
                """, Long.class, productId);
        jdbc.update("INSERT INTO recipe_items (recipe_id, ingredient_id, quantity) VALUES (?, ?, 1)",
                recipeId, ingredientId);
        mvc.perform(delete(INGREDIENTS + "/" + ingredientId).header("Authorization", bearer("MANAGER")))
                .andExpect(status().isUnprocessableContent());

        jdbc.update("UPDATE products SET is_active = false WHERE id = ?", productId);
        mvc.perform(delete(INGREDIENTS + "/" + ingredientId).header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNoContent());
        mvc.perform(delete(INGREDIENTS + "/" + ingredientId).header("Authorization", bearer("MANAGER")))
                .andExpect(status().isNoContent());
        mvc.perform(get(INGREDIENTS).header("Authorization", bearer("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + ingredientId + ")]").doesNotExist());
        mvc.perform(get(INGREDIENTS).param("includeInactive", "true")
                        .header("Authorization", bearer("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + ingredientId + ")].active").value(false));
    }

    @Test
    void missingCategoryAndIngredientIdsReturnNotFound() throws Exception {
        String auth = bearer("MANAGER");
        mvc.perform(put(CATEGORIES + "/" + MISSING_ID).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"valid\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(CATEGORIES + "/" + MISSING_ID).header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(put(INGREDIENTS + "/" + MISSING_ID).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingredientJson("N4A-missing", "Missing ingredient", "ML")))
                .andExpect(status().isNotFound());
        mvc.perform(delete(INGREDIENTS + "/" + MISSING_ID).header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void changingIngredientSkuToAnotherIngredientsSkuReturnsConflict() throws Exception {
        String otherSku = fixture + "-other-sku";
        createIngredient(otherSku, fixture + "-other-ingredient", "ML");
        mvc.perform(put(INGREDIENTS + "/" + ingredientId).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingredientJson(otherSku, fixture + "-ingredient", "ML")))
                .andExpect(status().isConflict());
    }

    @Test
    void postgresEnforcesCategoryAndIngredientUniquenessAndIngredientUnitCheck() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO product_categories (name) VALUES (?)", fixture + "-category"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO ingredients (sku, name, unit, is_active) VALUES (?, 'Duplicate', 'ML', true)
                """, fixture + "-sku"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO ingredients (sku, name, unit, is_active) VALUES (?, 'Invalid unit', 'OUNCE', true)
                """, fixture + "-invalid-unit"));
    }

    @Test
    void beanValidationRejectsMalformedRequestsWithoutEchoingInput() throws Exception {
        mvc.perform(post(CATEGORIES).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
        mvc.perform(post(INGREDIENTS).header("Authorization", bearer("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingredientJson(" ", "Bad unit request", "OUNCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
    }

    private long createIngredient(String sku, String name, String unit) {
        return jdbc.queryForObject("""
                INSERT INTO ingredients (sku, name, unit, is_active)
                VALUES (?, ?, ?, true)
                RETURNING id
                """, Long.class, sku, name, unit);
    }

    private long createProduct(long category, String sku) {
        long venueId = jdbc.queryForObject("SELECT id FROM venues ORDER BY id LIMIT 1", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO products
                    (venue_id, category_id, sku, name, price, is_bottle_keepable,
                     is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, '4A test product', 100, false, true, 0, now(), now())
                RETURNING id
                """, Long.class, venueId, category, sku);
    }

    private void cleanupFixtures() {
        jdbc.update("""
                DELETE FROM recipe_items
                WHERE recipe_id IN (
                    SELECT r.id FROM recipes r JOIN products p ON p.id = r.product_id
                    WHERE p.sku LIKE 'N4A-%'
                ) OR ingredient_id IN (
                    SELECT id FROM ingredients WHERE sku LIKE 'N4A-%'
                )
                """);
        jdbc.update("""
                DELETE FROM recipes WHERE product_id IN (
                    SELECT id FROM products WHERE sku LIKE 'N4A-%'
                )
                """);
        jdbc.update("""
                DELETE FROM inventory_transactions WHERE inventory_item_id IN (
                    SELECT ii.id FROM inventory_items ii
                    JOIN ingredients i ON i.id = ii.ingredient_id WHERE i.sku LIKE 'N4A-%'
                )
                """);
        jdbc.update("""
                DELETE FROM inventory_items WHERE ingredient_id IN (
                    SELECT id FROM ingredients WHERE sku LIKE 'N4A-%'
                )
                """);
        jdbc.update("DELETE FROM products WHERE sku LIKE 'N4A-%'");
        jdbc.update("DELETE FROM ingredients WHERE sku LIKE 'N4A-%'");
        jdbc.update("DELETE FROM product_categories WHERE name LIKE 'N4A-%'");
    }

    private String bearer(String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("nightvenue")
                .subject("1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("role", role)
                .build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static String ingredientJson(String sku, String name, String unit) {
        return "{\"sku\":\"" + sku + "\",\"name\":\"" + name + "\",\"unit\":\"" + unit + "\"}";
    }

    private static long readId(String json) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(json).get("id").asLong();
    }

    private static int successStatus(String endpoint) {
        return endpoint.startsWith("POST") ? 201 : (endpoint.startsWith("DELETE") ? 204 : 200);
    }
}
