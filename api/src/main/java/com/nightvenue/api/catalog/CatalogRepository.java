package com.nightvenue.api.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Category> findCategories() {
        return findCategories(false);
    }

    public List<Category> findCategories(boolean includeInactive) {
        return jdbc.query("""
                SELECT id, name, sort_order, is_active FROM product_categories
                WHERE is_active OR ?
                ORDER BY sort_order, id
                """, (rs, row) -> new Category(rs.getLong("id"), rs.getString("name"),
                rs.getInt("sort_order"), rs.getBoolean("is_active")), includeInactive);
    }

    public Optional<Category> findCategory(long id) {
        return jdbc.query("""
                SELECT id, name, sort_order, is_active FROM product_categories WHERE id = ?
                """, (rs, row) -> new Category(rs.getLong("id"), rs.getString("name"),
                rs.getInt("sort_order"), rs.getBoolean("is_active")), id).stream().findFirst();
    }

    public Optional<Category> findCategoryForUpdate(long id) {
        return jdbc.query("""
                SELECT id, name, sort_order, is_active
                FROM product_categories WHERE id = ? FOR UPDATE
                """, (rs, row) -> new Category(rs.getLong("id"), rs.getString("name"),
                rs.getInt("sort_order"), rs.getBoolean("is_active")), id).stream().findFirst();
    }

    public Category createCategory(String name, int sortOrder) {
        return jdbc.queryForObject("""
                INSERT INTO product_categories (name, sort_order) VALUES (?, ?)
                RETURNING id, name, sort_order, is_active
                """, (rs, row) -> new Category(rs.getLong("id"), rs.getString("name"),
                rs.getInt("sort_order"), rs.getBoolean("is_active")), name, sortOrder);
    }

    public Optional<Category> updateCategory(long id, String name, Integer sortOrder) {
        return jdbc.query("""
                UPDATE product_categories
                SET name = ?, sort_order = COALESCE(?, sort_order)
                WHERE id = ?
                RETURNING id, name, sort_order, is_active
                """, (rs, row) -> new Category(rs.getLong("id"), rs.getString("name"),
                rs.getInt("sort_order"), rs.getBoolean("is_active")), name, sortOrder, id)
                .stream().findFirst();
    }

    public boolean hasActiveProducts(long categoryId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM products WHERE category_id = ? AND is_active = true
                )
                """, Boolean.class, categoryId);
        return Boolean.TRUE.equals(exists);
    }

    public void deactivateCategory(long id) {
        jdbc.update("UPDATE product_categories SET is_active = false WHERE id = ?", id);
    }

    public List<Ingredient> findIngredients(boolean includeInactive) {
        return jdbc.query("""
                SELECT id, sku, name, unit, is_active FROM ingredients
                WHERE is_active OR ?
                ORDER BY name, id
                """, (rs, row) -> new Ingredient(rs.getLong("id"), rs.getString("sku"),
                rs.getString("name"), rs.getString("unit"), rs.getBoolean("is_active")),
                includeInactive);
    }

    public Optional<Ingredient> findIngredient(long id) {
        return jdbc.query("""
                SELECT id, sku, name, unit, is_active FROM ingredients WHERE id = ?
                """, (rs, row) -> new Ingredient(rs.getLong("id"), rs.getString("sku"),
                rs.getString("name"), rs.getString("unit"), rs.getBoolean("is_active")),
                id).stream().findFirst();
    }

    public Ingredient createIngredient(String sku, String name, String unit) {
        long id = jdbc.queryForObject("""
                INSERT INTO ingredients (sku, name, unit, is_active) VALUES (?, ?, ?, true)
                RETURNING id
                """, Long.class, sku, name, unit);
        return new Ingredient(id, sku, name, unit, true);
    }

    public Optional<Ingredient> findIngredientForUpdate(long id) {
        return jdbc.query("""
                SELECT id, sku, name, unit, is_active
                FROM ingredients WHERE id = ? FOR UPDATE
                """, (rs, row) -> new Ingredient(rs.getLong("id"), rs.getString("sku"),
                rs.getString("name"), rs.getString("unit"), rs.getBoolean("is_active")),
                id).stream().findFirst();
    }

    public Ingredient updateIngredient(Ingredient current, String sku, String name, String unit) {
        jdbc.update("""
                UPDATE ingredients SET sku = ?, name = ?, unit = ? WHERE id = ?
                """, sku, name, unit, current.id());
        return new Ingredient(current.id(), sku, name, unit, current.active());
    }

    public boolean isReferencedInRecipesOrInventory(long ingredientId) {
        Boolean referenced = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM recipe_items WHERE ingredient_id = ?)
                    OR EXISTS (SELECT 1 FROM inventory_items WHERE ingredient_id = ?)
                """, Boolean.class, ingredientId, ingredientId);
        return Boolean.TRUE.equals(referenced);
    }

    public boolean isUsedByActiveRecipe(long ingredientId) {
        Boolean used = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM recipe_items ri
                    JOIN recipes r ON r.id = ri.recipe_id
                    JOIN products p ON p.id = r.product_id
                    WHERE ri.ingredient_id = ? AND p.is_active = true
                )
                """, Boolean.class, ingredientId);
        return Boolean.TRUE.equals(used);
    }

    public void deactivateIngredient(long id) {
        jdbc.update("UPDATE ingredients SET is_active = false WHERE id = ?", id);
    }

    public record Category(long id, String name, int sortOrder, boolean active) {
    }

    public record Ingredient(long id, String sku, String name, String unit, boolean active) {
    }
}
