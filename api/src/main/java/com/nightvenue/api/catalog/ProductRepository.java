package com.nightvenue.api.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepository {
    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> findProducts(long venueId, boolean includeInactive) {
        return jdbc.query("""
                SELECT id, venue_id, category_id, sku, name, price, is_bottle_keepable,
                       bottle_volume_ml, is_active, version, updated_at
                FROM products
                WHERE venue_id = ? AND (is_active OR ?)
                ORDER BY name, id
                """, ProductRepository::mapProduct, venueId, includeInactive);
    }

    public Optional<Product> findProduct(long id) {
        return jdbc.query("""
                SELECT id, venue_id, category_id, sku, name, price, is_bottle_keepable,
                       bottle_volume_ml, is_active, version, updated_at
                FROM products WHERE id = ?
                """, ProductRepository::mapProduct, id).stream().findFirst();
    }

    public Product createProduct(
            long venueId, Long categoryId, String sku, String name, long price,
            boolean bottleKeepable, Integer bottleVolumeMl, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO products
                    (venue_id, category_id, sku, name, price, is_bottle_keepable,
                     bottle_volume_ml, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, 0, ?, ?)
                RETURNING id, venue_id, category_id, sku, name, price, is_bottle_keepable,
                          bottle_volume_ml, is_active, version, updated_at
                """, ProductRepository::mapProduct, venueId, categoryId, sku, name, price,
                bottleKeepable, bottleVolumeMl, Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<Product> updateProduct(
            long id, Long categoryId, String sku, String name, long price,
            boolean bottleKeepable, Integer bottleVolumeMl, Instant now) {
        return jdbc.query("""
                UPDATE products
                SET category_id = ?, sku = ?, name = ?, price = ?, is_bottle_keepable = ?,
                    bottle_volume_ml = ?, version = version + 1, updated_at = ?
                WHERE id = ?
                RETURNING id, venue_id, category_id, sku, name, price, is_bottle_keepable,
                          bottle_volume_ml, is_active, version, updated_at
                """, ProductRepository::mapProduct, categoryId, sku, name, price,
                bottleKeepable, bottleVolumeMl, Timestamp.from(now), id).stream().findFirst();
    }

    public void deactivateProduct(long id, Instant now) {
        jdbc.update("""
                UPDATE products
                SET is_active = false, version = version + 1, updated_at = ?
                WHERE id = ? AND is_active = true
                """, Timestamp.from(now), id);
    }

    public Optional<CategoryState> findCategory(long id) {
        return jdbc.query("""
                SELECT is_active FROM product_categories WHERE id = ? FOR SHARE
                """, (rs, row) -> new CategoryState(rs.getBoolean("is_active")), id)
                .stream().findFirst();
    }

    public List<Ingredient> lockIngredients(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("""
                SELECT id, sku, name, unit, is_active
                FROM ingredients
                WHERE id IN (%s)
                ORDER BY id ASC
                FOR UPDATE
                """.formatted(placeholders), (rs, row) -> new Ingredient(
                rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                rs.getString("unit"), rs.getBoolean("is_active")), ids.toArray());
    }

    public Optional<Recipe> findRecipe(long productId) {
        List<RecipeHeader> headers = jdbc.query("""
                SELECT id, product_id, version, updated_at FROM recipes WHERE product_id = ?
                """, (rs, row) -> new RecipeHeader(
                rs.getLong("id"), rs.getLong("product_id"), rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant()), productId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        RecipeHeader header = headers.get(0);
        List<RecipeItem> items = jdbc.query("""
                SELECT ri.ingredient_id, i.sku, i.name, i.unit, ri.quantity
                FROM recipe_items ri
                JOIN ingredients i ON i.id = ri.ingredient_id
                WHERE ri.recipe_id = ?
                ORDER BY ri.ingredient_id
                """, (rs, row) -> new RecipeItem(
                rs.getLong("ingredient_id"), rs.getString("sku"), rs.getString("name"),
                rs.getString("unit"), rs.getBigDecimal("quantity")), header.id());
        return Optional.of(new Recipe(header.productId(), header.version(), header.updatedAt(), items));
    }

    public RecipeHeader upsertRecipe(long productId, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO recipes (product_id, version, updated_at)
                VALUES (?, 1, ?)
                ON CONFLICT (product_id) DO UPDATE
                SET version = recipes.version + 1, updated_at = EXCLUDED.updated_at
                RETURNING id, product_id, version, updated_at
                """, (rs, row) -> new RecipeHeader(
                rs.getLong("id"), rs.getLong("product_id"), rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant()), productId, Timestamp.from(now));
    }

    public void deleteRecipeItems(long recipeId) {
        jdbc.update("DELETE FROM recipe_items WHERE recipe_id = ?", recipeId);
    }

    public void insertRecipeItem(long recipeId, long ingredientId, BigDecimal quantity) {
        jdbc.update("""
                INSERT INTO recipe_items (recipe_id, ingredient_id, quantity)
                VALUES (?, ?, ?)
                """, recipeId, ingredientId, quantity);
    }

    private static Product mapProduct(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Product(
                rs.getLong("id"), rs.getLong("venue_id"),
                rs.getObject("category_id", Long.class), rs.getString("sku"), rs.getString("name"),
                rs.getLong("price"), rs.getBoolean("is_bottle_keepable"),
                rs.getObject("bottle_volume_ml", Integer.class), rs.getBoolean("is_active"),
                rs.getLong("version"), rs.getTimestamp("updated_at").toInstant());
    }

    public record Product(
            long id, long venueId, Long categoryId, String sku, String name, long price,
            boolean bottleKeepable, Integer bottleVolumeMl, boolean active, long version,
            Instant updatedAt) {
    }

    public record CategoryState(boolean active) {
    }

    public record Ingredient(long id, String sku, String name, String unit, boolean active) {
    }

    public record RecipeHeader(long id, long productId, long version, Instant updatedAt) {
    }

    public record Recipe(long productId, long version, Instant updatedAt, List<RecipeItem> items) {
    }

    public record RecipeItem(
            long ingredientId, String ingredientSku, String ingredientName, String unit,
            BigDecimal quantity) {
    }
}
