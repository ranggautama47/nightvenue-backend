package com.nightvenue.api.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/db-integrity")
class TestCatalogDatabaseIntegrityController {
    private final JdbcTemplate jdbc;

    TestCatalogDatabaseIntegrityController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/fk/{recipeId}/{ingredientId}")
    void insertRecipeItemWithMissingIngredient(
            @PathVariable long recipeId, @PathVariable long ingredientId) {
        jdbc.update("""
                INSERT INTO recipe_items (recipe_id, ingredient_id, quantity)
                VALUES (?, ?, 1)
                """, recipeId, ingredientId);
    }
}
