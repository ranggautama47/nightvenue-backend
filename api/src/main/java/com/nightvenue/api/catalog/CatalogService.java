package com.nightvenue.api.catalog;

import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.ResourceNotFound;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Locale;

@Service
@Validated
public class CatalogService {
    private final CatalogRepository catalog;
    private final ApplicationEventPublisher events;

    public CatalogService(CatalogRepository catalog, ApplicationEventPublisher events) {
        this.catalog = catalog;
        this.events = events;
    }

    public List<CatalogRepository.Category> categories(boolean includeInactive) {
        return catalog.findCategories(includeInactive);
    }

    @Transactional
    public CatalogRepository.Category createCategory(CategoryRequest request) {
        String name = request.name().trim();
        CatalogRepository.Category created = CatalogWriteSupport.persist(
                () -> catalog.createCategory(name, request.sortOrder() == null ? 0 : request.sortOrder()));
        events.publishEvent(CatalogMutatedEvent.forAllVenues());
        return created;
    }

    @Transactional
    public CatalogRepository.Category updateCategory(@Positive long id, CategoryRequest request) {
        String name = request.name().trim();
        CatalogRepository.Category updated = CatalogWriteSupport.persist(() -> catalog.updateCategory(id, name, request.sortOrder())
                .orElseThrow(ResourceNotFound::new));
        events.publishEvent(CatalogMutatedEvent.forAllVenues());
        return updated;
    }

    @Transactional
    public void deactivateCategory(@Positive long id) {
        CatalogRepository.Category category = catalog.findCategoryForUpdate(id)
                .orElseThrow(ResourceNotFound::new);
        if (!category.active()) {
            return;
        }
        if (catalog.hasActiveProducts(id)) {
            throw new BusinessRuleViolation("Cannot deactivate category with active products");
        }
        catalog.deactivateCategory(id);
        events.publishEvent(CatalogMutatedEvent.forAllVenues());
    }

    public List<CatalogRepository.Ingredient> ingredients(boolean includeInactive) {
        return catalog.findIngredients(includeInactive);
    }

    public CatalogRepository.Ingredient createIngredient(IngredientRequest request) {
        String sku = request.sku().trim();
        String name = request.name().trim();
        String unit = normalizeUnit(request.unit());
        return CatalogWriteSupport.persist(() -> catalog.createIngredient(sku, name, unit));
    }

    @Transactional
    public CatalogRepository.Ingredient updateIngredient(@Positive long id, IngredientRequest request) {
        CatalogRepository.Ingredient current = catalog.findIngredientForUpdate(id)
                .orElseThrow(ResourceNotFound::new);
        String sku = request.sku().trim();
        String name = request.name().trim();
        String unit = normalizeUnit(request.unit());
        if (!current.unit().equals(unit) && catalog.isReferencedInRecipesOrInventory(id)) {
            throw new BusinessRuleViolation("Cannot change unit of referenced ingredient");
        }
        return CatalogWriteSupport.persist(() -> catalog.updateIngredient(current, sku, name, unit));
    }

    @Transactional
    public void deactivateIngredient(@Positive long id) {
        CatalogRepository.Ingredient ingredient = catalog.findIngredientForUpdate(id)
                .orElseThrow(ResourceNotFound::new);
        if (!ingredient.active()) {
            return;
        }
        if (catalog.isUsedByActiveRecipe(id)) {
            throw new BusinessRuleViolation("Ingredient is used by an active recipe");
        }
        catalog.deactivateIngredient(id);
    }

    private static String normalizeUnit(String unit) {
        return unit.trim().toUpperCase(Locale.ROOT);
    }

    public record CategoryRequest(@NotBlank @Size(max = 100) String name, Integer sortOrder) {
    }

    public record IngredientRequest(
            @NotBlank @Size(max = 40) String sku,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "(?i)\\s*(?:ML|GRAM|PIECE)\\s*") String unit) {
    }
}
