package com.nightvenue.api.catalog;

import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.ResourceNotFound;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;

@Service
@Validated
public class RecipeService {
    private final ProductRepository products;
    private final VenueAccessService venues;
    private final Clock clock;

    public RecipeService(ProductRepository products, VenueAccessService venues, Clock clock) {
        this.products = products;
        this.venues = venues;
        this.clock = clock;
    }

    public ProductRepository.Recipe recipe(long productId) {
        requireProductAccess(productId);
        return products.findRecipe(productId).orElseThrow(ResourceNotFound::new);
    }

    @Transactional
    public ProductRepository.Recipe replaceRecipe(long productId, RecipeRequest request) {
        requireProductAccess(productId);
        if (request.items().isEmpty()) {
            throw new BusinessRuleViolation("Recipe must contain at least one item");
        }

        List<Long> ingredientIds = request.items().stream()
                .map(RecipeItemRequest::ingredientId)
                .toList();
        if (new HashSet<>(ingredientIds).size() != ingredientIds.size()) {
            throw new BusinessRuleViolation(
                    "A recipe cannot contain the same ingredient more than once");
        }

        List<Long> lockOrder = ingredientIds.stream().sorted().toList();
        List<ProductRepository.Ingredient> lockedIngredients = products.lockIngredients(lockOrder);
        if (lockedIngredients.size() != lockOrder.size()) {
            throw new BusinessRuleViolation("A recipe references an ingredient that does not exist");
        }
        if (lockedIngredients.stream().anyMatch(ingredient -> !ingredient.active())) {
            throw new BusinessRuleViolation("Cannot use an inactive ingredient");
        }

        ProductRepository.RecipeHeader recipe = products.upsertRecipe(productId, clock.instant());
        products.deleteRecipeItems(recipe.id());
        for (RecipeItemRequest item : request.items()) {
            CatalogWriteSupport.persist(() -> {
                products.insertRecipeItem(recipe.id(), item.ingredientId(), item.quantity());
                return Boolean.TRUE;
            });
        }
        return products.findRecipe(productId).orElseThrow(IllegalStateException::new);
    }

    private void requireProductAccess(long productId) {
        ProductRepository.Product product =
                products.findProduct(productId).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(product.venueId());
    }

    public record RecipeRequest(
            @NotNull @Size(max = 100) List<@NotNull @Valid RecipeItemRequest> items) {
    }

    public record RecipeItemRequest(
            @NotNull @Positive Long ingredientId,
            @NotNull @Positive @Digits(integer = 11, fraction = 3)
            BigDecimal quantity) {
    }
}
