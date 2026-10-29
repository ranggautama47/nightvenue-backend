package com.nightvenue.api.inventory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryService.ProvisionedInventoryItem create(
            @Valid @RequestBody InventoryService.ProvisionRequest request) {
        return inventory.create(request);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public List<InventoryRepository.InventoryItem> items(
            @Positive @RequestParam long venueId,
            @Positive @RequestParam(required = false) Long locationId,
            @Positive @RequestParam(required = false) Long ingredientId) {
        return inventory.items(venueId, locationId, ingredientId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public InventoryRepository.InventoryItem item(@PathVariable @Positive long id) {
        return inventory.item(id);
    }

    @GetMapping("/{id}/transactions")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public List<InventoryRepository.InventoryTransaction> transactions(
            @PathVariable @Positive long id,
            @Min(1) @Max(500) @RequestParam(required = false) Integer limit) {
        return inventory.transactions(id, limit);
    }

    @PostMapping("/{id}/adjust")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public InventoryService.AdjustmentResult adjust(
            @PathVariable @Positive long id,
            @Valid @RequestBody InventoryService.AdjustmentRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest) {
        return inventory.adjust(id, request, Long.parseLong(jwt.getSubject()), servletRequest.getRemoteAddr());
    }
}
