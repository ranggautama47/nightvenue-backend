package com.nightvenue.api.inventory;

import com.nightvenue.api.common.Conflict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class InventoryDeductService {
    private final InventoryRepository inventory;
    private final Clock clock;

    public InventoryDeductService(InventoryRepository inventory, Clock clock) {
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deductForReference(
            long venueId,
            long locationId,
            List<IngredientQty> lines,
            String referenceType,
            long referenceId,
            LocalDate businessDate,
            long actorUserId) {
        Objects.requireNonNull(lines);
        Objects.requireNonNull(referenceType);
        Objects.requireNonNull(businessDate);
        if (!"ORDER_ITEM".equals(referenceType)) {
            throw new IllegalArgumentException("Internal stock deductions require an ORDER_ITEM reference");
        }
        lines.stream().sorted(Comparator.comparingLong(IngredientQty::ingredientId)).forEach(line -> {
            if (line.quantity() == null || line.quantity().signum() <= 0
                    || line.quantity().scale() > 3 || line.ingredientId() <= 0) {
                throw new IllegalArgumentException("Invalid inventory deduction line");
            }
            Instant now = clock.instant();
            InventoryRepository.DeductedItem updated = inventory.deduct(
                    venueId, locationId, line.ingredientId(), line.quantity(), now)
                    .orElseThrow(() -> new Conflict("Insufficient stock"));
            inventory.insertTransaction(
                    updated.id(), "SALE_DEDUCT", line.quantity().negate(), updated.quantity(),
                    businessDate, referenceType, referenceId, null, actorUserId, now);
        });
    }

    public record IngredientQty(long ingredientId, BigDecimal quantity) {
    }
}
