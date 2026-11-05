package com.nightvenue.api.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryRestoreService {
    private final InventoryRestoreRepository ledger;
    private final InventoryRepository inventory;

    public InventoryRestoreService(InventoryRestoreRepository ledger, InventoryRepository inventory) {
        this.ledger = ledger;
        this.inventory = inventory;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(List<Long> orderedItemIds, Map<Long, Long> voidLogIds,
                        long actorId, LocalDate businessDate, Instant now) {
        List<InventoryRestoreRepository.SaleDeduction> rows = ledger.findSaleDeductions(orderedItemIds);
        Map<Long, Integer> counts = new HashMap<>();
        rows.forEach(row -> counts.merge(row.orderItemId(), 1, Integer::sum));
        for (long itemId : orderedItemIds) {
            if (counts.getOrDefault(itemId, 0) == 0) {
                throw new IllegalStateException("Missing SALE_DEDUCT rows for order item " + itemId);
            }
        }

        Map<Long, BigDecimal> balances = new HashMap<>();
        for (InventoryRestoreRepository.SaleDeduction row : rows) {
            BigDecimal balance = balances.get(row.inventoryItemId());
            if (balance == null) {
                InventoryRepository.LockedInventoryItem locked = inventory.findForUpdate(row.inventoryItemId())
                        .orElseThrow(() -> new IllegalStateException("Inventory item not found: " + row.inventoryItemId()));
                balance = locked.quantity();
            }
            BigDecimal delta = row.delta().negate();
            balance = balance.add(delta).setScale(3);
            InventoryRepository.UpdateResult updated = inventory.updateQuantity(row.inventoryItemId(), balance, now);
            inventory.insertTransaction(row.inventoryItemId(), "VOID_RETURN", delta, updated.quantity(),
                    businessDate, "VOID_LOG", voidLogIds.get(row.orderItemId()), null, actorId, now);
            balances.put(row.inventoryItemId(), updated.quantity());
        }
    }
}
