package com.nightvenue.api.catalog;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public List<CatalogRepository.Category> categories(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return catalog.categories(includeInactive);
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogRepository.Category createCategory(@Valid @RequestBody CatalogService.CategoryRequest request) {
        return catalog.createCategory(request);
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public CatalogRepository.Category updateCategory(
            @PathVariable long id, @Valid @RequestBody CatalogService.CategoryRequest request) {
        return catalog.updateCategory(id, request);
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateCategory(@PathVariable long id) {
        catalog.deactivateCategory(id);
    }

    @GetMapping("/ingredients")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public List<CatalogRepository.Ingredient> ingredients(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return catalog.ingredients(includeInactive);
    }

    @PostMapping("/ingredients")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogRepository.Ingredient createIngredient(
            @Valid @RequestBody CatalogService.IngredientRequest request) {
        return catalog.createIngredient(request);
    }

    @PutMapping("/ingredients/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public CatalogRepository.Ingredient updateIngredient(
            @PathVariable long id, @Valid @RequestBody CatalogService.IngredientRequest request) {
        return catalog.updateIngredient(id, request);
    }

    @DeleteMapping("/ingredients/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateIngredient(@PathVariable long id) {
        catalog.deactivateIngredient(id);
    }
}
