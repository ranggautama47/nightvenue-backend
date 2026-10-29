package com.nightvenue.api.catalog;

import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.ResourceNotFound;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;
import java.util.List;

@Service
@Validated
public class ProductService {
    private final ProductRepository products;
    private final VenueAccessService venues;
    private final Clock clock;

    public ProductService(ProductRepository products, VenueAccessService venues, Clock clock) {
        this.products = products;
        this.venues = venues;
        this.clock = clock;
    }

    public List<ProductRepository.Product> products(long venueId, boolean includeInactive) {
        venues.requireAccess(venueId);
        boolean showInactive = includeInactive && !isCashier();
        return products.findProducts(venueId, showInactive);
    }

    public ProductRepository.Product product(long id, boolean includeInactive) {
        ProductRepository.Product product = products.findProduct(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(product.venueId());
        if (!product.active() && (!includeInactive || isCashier())) {
            throw new ResourceNotFound();
        }
        return product;
    }

    @Transactional
    public ProductRepository.Product createProduct(ProductCreateRequest request) {
        venues.requireAccess(request.venueId());
        validateBottle(request.bottleKeepable(), request.bottleVolumeMl());
        validateCategory(request.categoryId());
        return CatalogWriteSupport.persist(() -> products.createProduct(
                request.venueId(), request.categoryId(), request.sku().trim(), request.name().trim(),
                request.price(), request.bottleKeepable(), request.bottleVolumeMl(), clock.instant()));
    }

    @Transactional
    public ProductRepository.Product updateProduct(long id, ProductUpdateRequest request) {
        ProductRepository.Product existing = products.findProduct(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(existing.venueId());
        validateBottle(request.bottleKeepable(), request.bottleVolumeMl());
        validateCategory(request.categoryId());
        return CatalogWriteSupport.persist(() -> products.updateProduct(
                id, request.categoryId(), request.sku().trim(), request.name().trim(),
                request.price(), request.bottleKeepable(), request.bottleVolumeMl(), clock.instant())
                .orElseThrow(ResourceNotFound::new));
    }

    @Transactional
    public void deactivateProduct(long id) {
        ProductRepository.Product product = products.findProduct(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(product.venueId());
        products.deactivateProduct(id, clock.instant());
    }

    private void validateCategory(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        ProductRepository.CategoryState category =
                products.findCategory(categoryId).orElseThrow(
                        () -> new BusinessRuleViolation("Category does not exist"));
        if (!category.active()) {
            throw new BusinessRuleViolation("Cannot assign an inactive category");
        }
    }

    private static void validateBottle(boolean bottleKeepable, Integer bottleVolumeMl) {
        if (bottleKeepable && bottleVolumeMl == null) {
            throw new BusinessRuleViolation("Bottle keepable products require a bottle volume");
        }
    }

    private static boolean isCashier() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_CASHIER"::equals);
    }

    public record ProductCreateRequest(
            @NotNull @Positive Long venueId,
            @Positive Long categoryId,
            @NotBlank @Size(max = 40) String sku,
            @NotBlank @Size(max = 150) String name,
            @NotNull @PositiveOrZero Long price,
            @NotNull Boolean bottleKeepable,
            @Positive Integer bottleVolumeMl) {
    }

    public record ProductUpdateRequest(
            @Positive Long categoryId,
            @NotBlank @Size(max = 40) String sku,
            @NotBlank @Size(max = 150) String name,
            @NotNull @PositiveOrZero Long price,
            @NotNull Boolean bottleKeepable,
            @Positive Integer bottleVolumeMl) {
    }
}
