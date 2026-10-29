package com.nightvenue.api.inventory;

import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.businessday.BusinessDateService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.InvalidRequest;
import com.nightvenue.api.common.ResourceNotFound;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@Validated
public class InventoryService {
    private static final String UNIQUE_CONFLICT = "Inventory item already exists";
    private static final String INSUFFICIENT_STOCK = "Insufficient stock";
    private static final String VERSION_CONFLICT = "Inventory version conflict";

    private final InventoryRepository inventory;
    private final StockLocationRepository locations;
    private final VenueAccessService venues;
    private final BusinessDateService businessDates;
    private final InventoryAuditWriter audit;
    private final Clock clock;

    public InventoryService(
            InventoryRepository inventory,
            StockLocationRepository locations,
            VenueAccessService venues,
            BusinessDateService businessDates,
            InventoryAuditWriter audit,
            Clock clock) {
        this.inventory = inventory;
        this.locations = locations;
        this.venues = venues;
        this.businessDates = businessDates;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ProvisionedInventoryItem create(ProvisionRequest request) {
        InventoryRepository.LocationVenue location =
                inventory.findLocationVenueForUpdate(request.locationId()).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(location.venueId());
        if (!inventory.lockActiveIngredient(request.ingredientId())) {
            throw new BusinessRuleViolation("Ingredient does not exist or is inactive");
        }
        InventoryRepository.InventoryItem created = InventoryWriteSupport.persist(
                () -> inventory.create(
                        request.locationId(), request.ingredientId(), request.reorderLevel(), clock.instant()),
                UNIQUE_CONFLICT);
        return new ProvisionedInventoryItem(
                created.id(), created.locationId(), created.ingredientId(),
                created.quantity().toPlainString(), created.reorderLevel().toPlainString(), created.version());
    }

    public List<InventoryRepository.InventoryItem> items(long venueId, Long locationId, Long ingredientId) {
        venues.requireAccess(venueId);
        return inventory.findByVenue(venueId, locationId, ingredientId);
    }

    public InventoryRepository.InventoryItem item(long id) {
        InventoryRepository.InventoryItem item = inventory.findItem(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(item.venueId());
        return item;
    }

    public List<InventoryRepository.InventoryTransaction> transactions(long id, Integer limit) {
        item(id);
        return inventory.transactions(id, limit == null ? 100 : limit);
    }

    @Transactional
    public AdjustmentResult adjust(long id, AdjustmentRequest request, long actorUserId, String ipAddress) {
        InventoryRepository.LockedInventoryItem current =
                inventory.findForUpdate(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(current.venueId());

        if (request.delta().signum() == 0) {
            throw new InvalidRequest("Request validation failed");
        }
        String type = request.type();
        if ("PURCHASE_IN".equals(type) && request.delta().signum() < 0) {
            throw new BusinessRuleViolation("PURCHASE_IN delta must be positive");
        }
        if ("WASTE".equals(type) && request.delta().signum() > 0) {
            throw new BusinessRuleViolation("WASTE delta must be negative");
        }
        if (("WASTE".equals(type) || "ADJUSTMENT".equals(type))
                && (request.reason() == null || request.reason().isBlank())) {
            throw new BusinessRuleViolation("Reason is required");
        }

        if (current.version() != request.expectedVersion()) {
            throw new Conflict(VERSION_CONFLICT);
        }
        BigDecimal newQuantity = current.quantity().add(request.delta());
        if (newQuantity.signum() < 0) {
            throw new Conflict(INSUFFICIENT_STOCK);
        }

        var now = clock.instant();
        InventoryRepository.UpdateResult updated = inventory.updateQuantity(id, newQuantity, now);
        LocalDate businessDate = businessDates.businessDate(
                now, ZoneId.of(current.timezone()), current.cutoffHour());
        long transactionId = inventory.insertTransaction(
                id, type, request.delta(), updated.quantity(), businessDate,
                "MANUAL", null, request.reason(), actorUserId, now);
        audit.adjustment(current.venueId(), actorUserId, id, type, request.delta(), current.quantity(),
                updated.quantity(), request.reason(), ipAddress, now);
        return new AdjustmentResult(
                id, current.quantity(), updated.quantity(), request.delta(), type,
                request.reason(), businessDate, transactionId, updated.version());
    }

    public record ProvisionRequest(
            @NotNull @Positive Long locationId,
            @NotNull @Positive Long ingredientId,
            @NotNull @PositiveOrZero @Digits(integer = 11, fraction = 3) BigDecimal reorderLevel) {
    }

    public record AdjustmentRequest(
            @NotNull @Pattern(regexp = "PURCHASE_IN|WASTE|ADJUSTMENT") String type,
            @NotNull @Digits(integer = 11, fraction = 3) BigDecimal delta,
            @Size(max = 255) String reason,
            @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record ProvisionedInventoryItem(
            long id, long locationId, long ingredientId, String quantity, String reorderLevel, long version) {
    }

    public record AdjustmentResult(
            long inventoryItemId, BigDecimal previousQuantity, BigDecimal newQuantity, BigDecimal delta,
            String type, String reason, LocalDate businessDate, long transactionId, long version) {
    }
}
