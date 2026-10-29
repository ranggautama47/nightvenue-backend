package com.nightvenue.api.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
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
@Validated
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService products;
    private final RecipeService recipes;

    public ProductController(ProductService products, RecipeService recipes) {
        this.products = products;
        this.recipes = recipes;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public List<ProductRepository.Product> products(
            @Positive @RequestParam long venueId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @AuthenticationPrincipal Jwt jwt) {
        return products.products(venueId, includeInactive && !"CASHIER".equals(jwt.getClaimAsString("role")));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductRepository.Product createProduct(
            @Valid @RequestBody ProductService.ProductCreateRequest request) {
        return products.createProduct(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ProductRepository.Product product(
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return products.product(id, includeInactive);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ProductRepository.Product updateProduct(
            @PathVariable @Positive long id,
            @Valid @RequestBody ProductService.ProductUpdateRequest request) {
        return products.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateProduct(@PathVariable @Positive long id) {
        products.deactivateProduct(id);
    }

    @GetMapping("/{id}/recipe")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ProductRepository.Recipe recipe(@PathVariable @Positive long id) {
        return recipes.recipe(id);
    }

    @PutMapping("/{id}/recipe")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ProductRepository.Recipe replaceRecipe(
            @PathVariable @Positive long id,
            @Valid @RequestBody RecipeService.RecipeRequest request) {
        return recipes.replaceRecipe(id, request);
    }

}
