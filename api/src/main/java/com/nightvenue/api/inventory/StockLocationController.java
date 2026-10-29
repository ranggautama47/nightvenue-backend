package com.nightvenue.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
@RequestMapping("/api/v1/stock-locations")
public class StockLocationController {
    private final StockLocationService locations;

    public StockLocationController(StockLocationService locations) {
        this.locations = locations;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public List<StockLocationRepository.StockLocation> locations(
            @Positive @RequestParam long venueId) {
        return locations.locations(venueId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public StockLocationRepository.StockLocation location(@PathVariable @Positive long id) {
        return locations.location(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public StockLocationRepository.StockLocation create(
            @Valid @RequestBody StockLocationService.CreateRequest request) {
        return locations.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public StockLocationRepository.StockLocation update(
            @PathVariable @Positive long id,
            @Valid @RequestBody StockLocationService.UpdateRequest request) {
        return locations.update(id, request);
    }
}
