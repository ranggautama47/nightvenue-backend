package com.nightvenue.api.inventory;

import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.ResourceNotFound;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Service
@Validated
public class StockLocationService {
    private static final String UNIQUE_CONFLICT = "Stock location already exists";

    private final StockLocationRepository locations;
    private final VenueAccessService venues;

    public StockLocationService(StockLocationRepository locations, VenueAccessService venues) {
        this.locations = locations;
        this.venues = venues;
    }

    public List<StockLocationRepository.StockLocation> locations(long venueId) {
        venues.requireAccess(venueId);
        return locations.findByVenue(venueId);
    }

    public StockLocationRepository.StockLocation location(long id) {
        StockLocationRepository.StockLocation location =
                locations.findById(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(location.venueId());
        return location;
    }

    @Transactional
    public StockLocationRepository.StockLocation create(CreateRequest request) {
        if (isAdmin()) {
            if (!locations.activeVenueExists(request.venueId())) {
                throw new BusinessRuleViolation("Venue does not exist or is inactive");
            }
        }
        venues.requireAccess(request.venueId());
        return InventoryWriteSupport.persist(
                () -> locations.create(request.venueId(), request.name().trim(), request.type()),
                UNIQUE_CONFLICT);
    }

    @Transactional
    public StockLocationRepository.StockLocation update(long id, UpdateRequest request) {
        StockLocationRepository.StockLocation current =
                locations.findByIdForUpdate(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(current.venueId());
        if (request.venueId() != current.venueId()) {
            throw new BusinessRuleViolation("Cannot move stock location to another venue");
        }
        if (!request.type().equals(current.type()) && locations.hasInventory(id)) {
            throw new BusinessRuleViolation("Cannot change stock location type with inventory");
        }
        return InventoryWriteSupport.persist(
                () -> locations.update(id, request.name().trim(), request.type())
                        .orElseThrow(ResourceNotFound::new),
                UNIQUE_CONFLICT);
    }

    private static boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    public record CreateRequest(
            @NotNull @Positive Long venueId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "WAREHOUSE|BAR") String type) {
    }

    public record UpdateRequest(
            @NotNull @Positive Long venueId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "WAREHOUSE|BAR") String type) {
    }
}
